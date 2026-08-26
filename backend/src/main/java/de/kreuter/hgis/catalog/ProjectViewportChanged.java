package de.kreuter.hgis.catalog;

import java.util.UUID;

/**
 * This project's own map viewport -- {@code center} and {@code zoom} -- changed to what
 * {@code GET /api/projects/{id}} now reports.
 *
 * <p>Neither {@link ProjectViewStateChanged} nor {@code CatalogChanged} covers this. The
 * former is a client's own <em>local</em> working state -- active layer, per-layer sort,
 * query and selection -- never a column of the project row itself. The latter is the
 * layer catalog -- list, style, data -- and its documented receiver reaction is "reread
 * {@code GET .../layers}", a response that does not even carry a project's center or
 * zoom (see that class's own javadoc). A viewport belongs to the project row, which is
 * neither of those two things, hence a name of its own (TASKS.md Aufgabe 9).
 *
 * <p>Published only when {@code center} or {@code zoom} actually moved -- see {@link
 * ProjectService#update} for the comparison that enforces this. {@code
 * ProjectDtos.UpdateRequest} also carries {@code name}, {@code description}, {@code
 * basemap} and {@code basemapOpacity}, and a plain rename must not wake every open tab's
 * map: the one thing this event is for is precisely the thing that must stay rare.
 *
 * <p>Carries no working data of its own, the same rule {@link ProjectViewStateChanged}
 * and {@code CatalogChanged} both follow: whoever hears it rereads
 * {@code GET /api/projects/{id}}, so this stays independent of every later change to
 * that response's shape.
 *
 * <p>Published by {@link ProjectService#update} and consumed after commit by {@code
 * de.kreuter.hgis.events} -- this package knows nothing about how it travels.
 *
 * @param origin the {@code X-Hgis-Client} of whoever caused the change, or null when the
 *     caller named none -- same rule as {@link ProjectViewStateChanged#origin}: it exists
 *     so the writer recognises its own echo instead of answering it. Every writer of
 *     {@code PATCH /api/projects/{id}} is synchronous -- it always answers with the
 *     result it just wrote -- so, unlike {@code CatalogChanged#origin}, there is no
 *     second, asynchronous case that would need the header suppressed.
 */
public record ProjectViewportChanged(UUID projectId, String origin) {
}
