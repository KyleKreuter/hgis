package de.kreuter.hgis.export;

/**
 * One attribute of a layer, resolved for export: the SQL column it is read from and the
 * JSON property key it is written under.
 *
 * <p>Keeping the pair together is what lets the streaming step run without touching the
 * catalog again -- and, more to the point, without a {@code LayerField} entity travelling
 * onto the async thread that writes the response, where its session is long gone.
 *
 * @param columnName from {@code layer_field.column_name}; the only string of the two that
 *                   is ever allowed into SQL, and only through
 *                   {@link de.kreuter.hgis.common.SqlIdentifier}
 * @param propertyKey what the attribute is called in the exported file, see
 *                    {@link PropertyNaming}
 */
public record ExportField(String columnName, String propertyKey) {
}
