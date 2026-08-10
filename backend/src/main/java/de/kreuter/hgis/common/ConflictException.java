package de.kreuter.hgis.common;

import java.util.Map;

/**
 * Someone else wrote the row between reading and saving it.
 *
 * Carries the current server state so the UI can show what changed instead of just
 * refusing -- being told "conflict" without being told what conflicts leaves no way
 * forward except discarding the edit. Mapped to 409 by {@link ProblemDetailAdvice}.
 */
public class ConflictException extends RuntimeException {

	private final transient Map<String, Object> current;

	public ConflictException(String message, Map<String, Object> current) {
		super(message);
		this.current = current;
	}

	/** The row as it now stands on the server, or null when it was deleted meanwhile. */
	public Map<String, Object> getCurrent() {
		return current;
	}
}
