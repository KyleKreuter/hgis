package de.kreuter.hgis.common;

/**
 * How this build renders a tile. Part of every tile URL and every tile {@code ETag},
 * alongside a layer's {@code dataVersion}, {@code styleVersion} and {@code clipVersion}.
 *
 * <h2>Why this exists</h2>
 *
 * The other three versions all follow the data: they change when a layer's features,
 * its style or the masks acting on it change. None of them notices when the <em>meaning
 * of the rendering itself</em> changes -- when the same layer, style and masks are meant
 * to produce a different picture than they did in the last release.
 *
 * <p>That gap is not theoretical. CONTRACT.md phase 21a narrowed {@code insideWhole} from
 * "touches the mask" to "lies entirely within the mask". Every input to the tile address
 * stayed byte for byte identical, so every client kept showing the old cut -- and tiles go
 * out {@code Cache-Control: immutable} with a year's lifetime, so "kept showing" means
 * until someone clears their browser cache by hand. The change was correct, deployed, and
 * invisible.
 *
 * <p>Raising this constant makes every tile address in the system change at once, which is
 * exactly the blunt instrument the situation calls for: no per-layer invalidation, no
 * cache to purge, no state to get wrong.
 *
 * <h2>When to raise it</h2>
 *
 * Raise it in the same commit as any change that alters what a tile contains or looks like
 * for unchanged inputs. Some examples, all of which have or could have happened here:
 *
 * <ul>
 *   <li>a clip mode's meaning changes, or a new one starts applying differently</li>
 *   <li>the {@code ST_AsMVTGeom} extent or buffer changes</li>
 *   <li>which attributes a tile carries changes for a style that itself stayed the same</li>
 *   <li>the geometry simplification or the MVT layer name changes</li>
 * </ul>
 *
 * <p>Do <em>not</em> raise it for changes that cannot alter a rendered tile: a refactor
 * with identical SQL output, a new endpoint, a comment. A needless raise is not wrong, it
 * just throws away every cached tile for nothing.
 *
 * <p>There is deliberately no automation here -- no build hash, no timestamp. Both would
 * discard every tile on every deployment, which is precisely the cost this constant exists
 * to avoid paying unless it buys something.
 */
public final class TileRenderVersion {

	/**
	 * Current rendering contract.
	 *
	 * <p>History, so the next person can tell whether their change deserves a raise:
	 *
	 * <ul>
	 *   <li>{@code 1} -- introduced with CONTRACT.md phase 21a. Adding this component to
	 *       the tile address changed every URL by itself, so it did not need to start
	 *       above 1 to flush the caches holding the pre-21a clip semantics.</li>
	 * </ul>
	 */
	public static final int CURRENT = 1;

	private TileRenderVersion() {
	}
}
