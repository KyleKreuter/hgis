package de.kreuter.hgis.wms;

import de.kreuter.hgis.common.BadRequestException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.springframework.stereotype.Component;

/**
 * Refuses a WMS address before it is ever fetched, the sensitive half of stage 2: unlike
 * every other outbound call in this backend, which goes to Hamburg's own, fixed Geoportal
 * hosts, {@code /api/wms/capabilities?url=...} lets a client name <em>any</em> address and
 * asks the server to fetch it -- the textbook SSRF shape, so this guard runs ahead of both
 * the first request and every redirect hop {@link WmsCapabilitiesFetcher} follows.
 *
 * <p>Checked against the resolved IP address, never the hostname alone: a name is only a
 * label, and {@code my-attacker-domain.example} resolving to {@code 127.0.0.1} is exactly
 * as dangerous as the literal address would have been. Skipping the resolution and pattern
 * matching the string instead is the mistake this class exists to not make.
 *
 * <p>Not closed against DNS rebinding: the address is resolved and checked here, and the
 * HTTP client resolves the same hostname again, independently, when it actually connects.
 * An attacker who controls DNS and answers the two lookups differently -- a public address
 * for this check, a private one moments later for the real connection -- slips through.
 * Closing that needs the connection pinned to the address this method already resolved,
 * which the JDK's {@link java.net.http.HttpClient} has no supported hook for short of a
 * custom {@code java.net.spi.InetAddressResolverProvider}. Left as a known residual risk
 * rather than solved with an unsupported workaround; every other class of SSRF this stage
 * names -- a literal private address, a loopback name, a link-local target -- is covered.
 */
@Component
class WmsUrlGuard {

	private static final String REJECTED = "Diese Adresse ist nicht erlaubt.";

	/**
	 * @throws BadRequestException when the scheme is not http/https, the host is missing
	 *     or does not resolve, or any of its addresses fall in a private, loopback or
	 *     link-local range
	 */
	void requireAllowed(URI uri) {
		String scheme = uri.getScheme();
		if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
			throw new BadRequestException(REJECTED);
		}
		String host = uri.getHost();
		if (host == null || host.isBlank()) {
			throw new BadRequestException(REJECTED);
		}

		InetAddress[] addresses;
		try {
			addresses = InetAddress.getAllByName(host);
		}
		catch (UnknownHostException e) {
			throw new BadRequestException(REJECTED);
		}
		if (addresses.length == 0) {
			throw new BadRequestException(REJECTED);
		}
		for (InetAddress address : addresses) {
			if (isDisallowed(address)) {
				throw new BadRequestException(REJECTED);
			}
		}
	}

	/**
	 * The ranges the contract names, plus what the JDK's own address classifiers already
	 * catch for free ({@link InetAddress#isLoopbackAddress()} etc.) -- covering both so a
	 * range everyone would expect refused (loopback, multicast) is refused even where it
	 * was not spelled out.
	 */
	private static boolean isDisallowed(InetAddress address) {
		if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isAnyLocalAddress()
				|| address.isMulticastAddress()) {
			return true;
		}
		if (address instanceof Inet4Address) {
			return isDisallowedIpv4(address.getAddress());
		}
		if (address instanceof Inet6Address) {
			return isDisallowedIpv6(address.getAddress());
		}
		// Neither IPv4 nor IPv6: not a shape this JVM's resolver has ever been observed to
		// produce, and nothing this method knows how to classify as safe.
		return true;
	}

	/** 127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16. */
	private static boolean isDisallowedIpv4(byte[] a) {
		int b0 = a[0] & 0xFF;
		int b1 = a[1] & 0xFF;
		if (b0 == 127) return true;
		if (b0 == 10) return true;
		if (b0 == 172 && b1 >= 16 && b1 <= 31) return true;
		if (b0 == 192 && b1 == 168) return true;
		if (b0 == 169 && b1 == 254) return true;
		return false;
	}

	/**
	 * ::1 is already caught by {@link InetAddress#isLoopbackAddress()} above; what is left
	 * is fc00::/7, the unique local address range -- IPv6's rough counterpart to 10/8 and
	 * 192.168/16. {@link InetAddress#isSiteLocalAddress()} does not cover it: that method
	 * only recognises the older, deprecated fec0::/10 site-local prefix, not this one.
	 */
	private static boolean isDisallowedIpv6(byte[] a) {
		int first = a[0] & 0xFF;
		return (first & 0xFE) == 0xFC;
	}
}
