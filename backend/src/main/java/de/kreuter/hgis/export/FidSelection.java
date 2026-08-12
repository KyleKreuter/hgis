package de.kreuter.hgis.export;

import de.kreuter.hgis.common.BadRequestException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Which rows of a layer an export covers.
 *
 * <p>"No selection was sent" and "the selection is empty" are two different requests and
 * must stay that way. A map with nothing selected would otherwise silently export the
 * whole layer -- for a layer of any size that is the one outcome nobody asked for, and
 * the download is finished before it can be noticed. The absent parameter therefore
 * means the whole layer, and an empty one means an empty {@code FeatureCollection}.
 *
 * @param fids the rows to export, or {@code null} for the whole layer
 */
public record FidSelection(List<Long> fids) {

	/**
	 * Upper bound for one request. Far above any plausible on-screen selection, and low
	 * enough that a malicious body cannot turn into hundreds of megabytes of boxed longs.
	 */
	public static final int MAX_FIDS = 100_000;

	/** Enough of an offending token to recognise it, not enough to echo back a payload. */
	private static final int ECHO_LENGTH = 40;

	public FidSelection {
		if (fids != null) {
			if (fids.size() > MAX_FIDS) {
				throw new BadRequestException("Eine Auswahl darf höchstens " + MAX_FIDS
						+ " Objekte enthalten. Angefragt waren " + fids.size() + ".");
			}
			if (fids.stream().anyMatch(Objects::isNull)) {
				throw new BadRequestException("Die Auswahl enthält einen leeren Eintrag");
			}
			fids = List.copyOf(fids);
		}
	}

	/** No selection: everything the layer holds. */
	public static FidSelection wholeLayer() {
		return new FidSelection(null);
	}

	/**
	 * Reads the {@code fids} query parameter.
	 *
	 * @param raw {@code null} when the parameter is absent, which is the whole layer;
	 *            present but blank is the explicitly empty selection
	 */
	public static FidSelection parse(String raw) {
		if (raw == null) {
			return wholeLayer();
		}
		String trimmed = raw.trim();
		if (trimmed.isEmpty()) {
			return new FidSelection(List.of());
		}

		// -1 keeps trailing empties, so "1,2," is reported rather than quietly accepted:
		// a client that builds the list by concatenation should hear about it.
		String[] tokens = trimmed.split(",", -1);
		List<Long> fids = new ArrayList<>(tokens.length);
		for (String token : tokens) {
			fids.add(parseFid(token.trim()));
		}
		return new FidSelection(fids);
	}

	public boolean isWholeLayer() {
		return fids == null;
	}

	public boolean isEmptySelection() {
		return fids != null && fids.isEmpty();
	}

	private static long parseFid(String token) {
		try {
			return Long.parseLong(token);
		}
		catch (NumberFormatException ex) {
			throw new BadRequestException(
					"'fids' darf nur ganze Zahlen enthalten. Wert war: '" + echo(token) + "'.");
		}
	}

	private static String echo(String token) {
		return token.length() <= ECHO_LENGTH ? token : token.substring(0, ECHO_LENGTH) + "…";
	}
}
