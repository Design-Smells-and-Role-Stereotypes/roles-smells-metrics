package net.azib.ipscan.core.net;

import net.azib.ipscan.core.ScanningSubject;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JavaPingerTest {
	JavaPinger pinger = new JavaPinger(10);

	@Test
	public void pingAlive() throws IOException {
		PingResult result = pinger.ping(new ScanningSubject(InetAddress.getLocalHost()), 3);
		assertTrue(result.isAlive());
		assertTrue(result.getAverageTime() <= 10);
	}

	@Test
	public void pingDead() throws IOException {
		PingResult result = pinger.ping(new ScanningSubject(InetAddress.getByName("192.168.99.253")), 1);
		assertFalse(result.isAlive());
	}
}