package de.kreuter.hgis.catalog;

import java.util.UUID;

/**
 * A project's working state now stands at {@code version}.
 *
 * <p>Deliberately a state, not a change: it says where the project is, never what was
 * done to it. Nothing is gained by hearing it twice, nothing is lost by hearing only the
 * later of two -- which is what makes duplicate delivery, reordering and coalescing
 * harmless further down the line.
 *
 * <p>It carries no working state of its own either. Whoever hears it reads the state
 * through {@code GET /api/projects/{id}/view-state}, so this stays small and stays
 * independent of every later change to that document's format.
 *
 * <p>Published by {@link ProjectService#updateViewState} and consumed after commit by
 * {@code de.kreuter.hgis.events} -- this package knows nothing about how it travels.
 *
 * @param origin the {@code X-Hgis-Client} of whoever caused the change, or null when the
 *     caller named none. It exists so that caller can recognise its own echo instead of
 *     answering it; see {@code EventStreamController}.
 */
public record ProjectViewStateChanged(UUID projectId, long version, String origin) {
}
