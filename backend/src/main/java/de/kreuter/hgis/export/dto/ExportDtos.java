package de.kreuter.hgis.export.dto;

import de.kreuter.hgis.export.FidSelection;
import java.util.List;

/** Transport types for the export API. */
public final class ExportDtos {

	private ExportDtos() {
	}

	/**
	 * Body of a POST export.
	 *
	 * @param fids the rows to export. Absent or {@code null} means the whole layer, an
	 *             empty array means an empty {@code FeatureCollection} -- the same
	 *             distinction the {@code fids} query parameter makes, see
	 *             {@link FidSelection}.
	 */
	public record SelectionRequest(List<Long> fids) {

		public FidSelection toSelection() {
			return new FidSelection(fids);
		}
	}
}
