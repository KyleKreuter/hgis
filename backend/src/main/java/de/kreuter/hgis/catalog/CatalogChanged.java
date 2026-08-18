package de.kreuter.hgis.catalog;

import java.util.UUID;

/**
 * This project's catalog changed -- its layer list, a layer's properties, its style, its
 * data. Everything about a project that {@link ProjectViewStateChanged} does not already
 * cover, which is the workspace alone (map position, active layer, sort, query, selection).
 *
 * <p>Deliberately carries no version of its own, unlike {@link ProjectViewStateChanged}.
 * {@code project.catalog_version} is bumped by a database trigger on {@code layer} and
 * {@code layer_field} (see {@code V14__catalog_version.sql} for why a trigger and not a
 * Java counter), not by the write path that causes this event -- so nothing in Java ever
 * holds the fresh number at the moment this is published. {@code de.kreuter.hgis.events}
 * reads it back itself, after commit; see {@code CatalogEventBridge}.
 *
 * <p>Published by {@link CatalogTouch#touch}, not by each write path directly -- see that
 * class for why a shared choke point, and how it keeps one transaction that touches a
 * project's catalog several times over to a single published event.
 *
 * @param origin the {@code X-Hgis-Client} of whoever caused the change, or null when the
 *     writer named none, or deliberately null regardless of what the writer sent -- three
 *     different things this one field has to carry, and the reason is the same distinction
 *     {@link ProjectViewStateChanged#origin} never has to make.
 *
 *     <p>A synchronous write -- every layer, field, style, edit, split and merge in
 *     {@code de.kreuter.hgis.catalog} and {@code de.kreuter.hgis.features} -- answers its
 *     own HTTP request with the result it just wrote. Its caller already holds the new
 *     state by the time this event could possibly reach it, so echo suppression is exactly
 *     right: hearing its own name back means nothing further to do, the same reasoning
 *     {@link ProjectViewStateChanged#origin} rests on. These write paths thread the header
 *     through and this field carries it.
 *
 *     <p>An asynchronous one -- an import ({@code ImportTransactions}) or a duplicate
 *     ({@code ProjectDuplicateTransactions}) -- answers its HTTP request with a {@code Job}
 *     to poll, not the result: the caller has nothing yet. For these two, its own echo is
 *     not stale news to filter out, it is the one signal the caller is waiting for -- so
 *     wiring the header through would risk exactly that caller's own client suppressing
 *     the notification it needs most. Both publish with {@code origin} fixed to null,
 *     regardless of who started the job.
 */
public record CatalogChanged(UUID projectId, String origin) {
}
