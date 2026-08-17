package de.kreuter.hgis.changelog;

/**
 * The ten tokens {@code change_log.action} accepts (V13__trash_and_change_log.sql,
 * {@code change_log_action} CHECK). Held as plain string constants rather than an enum,
 * the same way {@link de.kreuter.hgis.catalog.Layer#getKind()} and
 * {@link de.kreuter.hgis.catalog.Layer#getClipMode()} are: the database CHECK constraint
 * is the single source of truth for which tokens are legal, not a Java type here.
 */
public final class ChangeLogAction {

	public static final String LAYER_CREATE = "layer.create";
	public static final String LAYER_UPDATE = "layer.update";
	public static final String LAYER_DELETE = "layer.delete";
	public static final String LAYER_RESTORE = "layer.restore";
	public static final String LAYER_PURGE = "layer.purge";

	public static final String FEATURE_INSERT = "feature.insert";
	public static final String FEATURE_UPDATE = "feature.update";
	public static final String FEATURE_DELETE = "feature.delete";

	public static final String FIELD_CREATE = "field.create";
	public static final String FIELD_DELETE = "field.delete";

	private ChangeLogAction() {
	}
}
