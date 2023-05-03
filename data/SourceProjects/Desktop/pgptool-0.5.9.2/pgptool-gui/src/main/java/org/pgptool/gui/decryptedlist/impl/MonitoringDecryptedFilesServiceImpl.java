/*******************************************************************************
 * PGPTool is a desktop application for pgp encryption/decryption
 * Copyright (C) 2019 Sergey Karpushin
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 ******************************************************************************/
package org.pgptool.gui.decryptedlist.impl;

import java.io.File;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;
import org.pgptool.gui.configpairs.api.ConfigPairs;
import org.pgptool.gui.decryptedlist.api.DecryptedFile;
import org.pgptool.gui.decryptedlist.api.MonitoringDecryptedFilesService;
import org.pgptool.gui.tools.fileswatcher.FilesWatcherHandler;
import org.pgptool.gui.tools.fileswatcher.MultipleFilesWatcher;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;

import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

public class MonitoringDecryptedFilesServiceImpl
		implements MonitoringDecryptedFilesService, InitializingBean, DisposableBean {
	private static Logger log = Logger.getLogger(MonitoringDecryptedFilesServiceImpl.class);

	protected static final long TIME_TO_ENSURE_FILE_WAS_DELETED_MS = 2000;

	public static final String PREFIX = "decrhist:";

	@Autowired
	private ConfigPairs monitoredDecrypted;

	private MultipleFilesWatcher multipleFilesWatcher;

	private Cache<String, DecryptedFile> recentlyRemoved = CacheBuilder.newBuilder()
			.expireAfterWrite(TIME_TO_ENSURE_FILE_WAS_DELETED_MS, TimeUnit.MILLISECONDS).build();

	@Override
	public void afterPropertiesSet() throws Exception {
		setupFileWatcher();
	}

	private void setupFileWatcher() {
		// TBD: Fix. Smells like DI violation
		multipleFilesWatcher = new MultipleFilesWatcher(dirWatcherHandler, "MonitoringDecryptedFilesService");
		List<DecryptedFile> entries = getDecryptedFiles();
		for (DecryptedFile entry : entries) {
			String decryptedFile = entry.getDecryptedFile();
			if (!new File(decryptedFile).exists()) {
				log.debug("Previously registered file is no longer exists, remove =ing it from the tracking "
						+ decryptedFile);
				remove(decryptedFile);
				continue;
			}
			multipleFilesWatcher.watchForFileChanges(decryptedFile);
		}
	}

	@Override
	public void destroy() throws Exception {
		multipleFilesWatcher.stopWatcher();
	}

	private FilesWatcherHandler dirWatcherHandler = new FilesWatcherHandler() {
		@Override
		public void handleFileChanged(Kind<?> operation, String fileAbsolutePathname) {
			if (StandardWatchEventKinds.ENTRY_CREATE.equals(operation)) {
				DecryptedFile recentlyRemovedEntry = recentlyRemoved.getIfPresent(fileAbsolutePathname);
				if (recentlyRemovedEntry != null) {
					addOrUpdate(recentlyRemovedEntry);
				}
				return;
			}

			if (StandardWatchEventKinds.ENTRY_DELETE.equals(operation)) {
				remove(fileAbsolutePathname);
				return;
			}

			// Other cases not supported -- not needed
		}
	};

	@Override
	public synchronized void addOrUpdate(DecryptedFile decryptedFile) {
		try {
			Preconditions.checkArgument(decryptedFile != null, "decryptedFile is NULL");
			Preconditions.checkArgument(decryptedFile.getDecryptedFile() != null,
					"decryptedFile.DecryptedFile is NULL");
			Preconditions.checkArgument(decryptedFile.getEncryptedFile() != null,
					"decryptedFile.EncryptedFile is NULL");

			String key = buildKey(decryptedFile.getDecryptedFile());
			monitoredDecrypted.put(key, decryptedFile);
			multipleFilesWatcher.watchForFileChanges(decryptedFile.getDecryptedFile());
		} catch (Throwable t) {
			throw new RuntimeException("Failed to update decrypted file for monitoring", t);
		}
	}

	@Override
	public synchronized void remove(String depcryptedFilePathname) {
		try {
			Preconditions.checkArgument(depcryptedFilePathname != null, "depcryptedFilePathname is NULL");

			String key = buildKey(depcryptedFilePathname);
			DecryptedFile existing = monitoredDecrypted.find(key, null);
			if (existing == null) {
				// if it's not there -- nothing to delete
				return;
			}

			// Remember this file in case it will appear right away
			recentlyRemoved.put(depcryptedFilePathname, existing);

			monitoredDecrypted.put(key, null);

			multipleFilesWatcher.stopWatchingFile(depcryptedFilePathname);
		} catch (Throwable t) {
			throw new RuntimeException("Failed to unregister decrypted file from monitoring", t);
		}
	}

	private String buildKey(String decryptedFile) {
		return decryptedFile;
	}

	@Override
	public synchronized List<DecryptedFile> getDecryptedFiles() {
		return new ArrayList<>(monitoredDecrypted.getAll().stream().map(x -> (DecryptedFile) x.getValue())
				.collect(Collectors.toList()));
	}

	@Override
	public DecryptedFile findByDecryptedFile(String decryptedFile) {
		return getDecryptedFiles().stream().filter(x -> x.getDecryptedFile().equals(decryptedFile)).findFirst()
				.orElse(null);
	}

	@Override
	public DecryptedFile findByEncryptedFile(String encryptedFile, Predicate<DecryptedFile> filter) {
		return getDecryptedFiles().stream().filter(x -> x.getEncryptedFile().equals(encryptedFile) && filter.test(x))
				.findFirst().orElse(null);
	}
}
