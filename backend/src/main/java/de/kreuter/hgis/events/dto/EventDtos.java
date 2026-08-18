package de.kreuter.hgis.events.dto;

import java.util.UUID;

/**
 * What travels over the live channel. Grouped in one file because these are small,
 * closely related and always read together -- the same arrangement as {@code ProjectDtos}.
 *
 * <p>Two rules hold for every record in here, and they are the whole reason the channel
 * is as small as it is:
 *
 * <ol>
 * <li><b>An event reports a state, never a change.</b> "Project X now stands at version
 *     42", never "something was selected in X". Hearing the same state twice therefore
 *     costs a repeated read and nothing else, and missing one is made good by the next.
 * <li><b>An event carries no working data.</b> Only identifiers and numbers. The receiver
 *     reads the content itself through the ordinary API, which keeps the channel
 *     independent of every later change to that content's format.
 * </ol>
 *
 * <p>A second kind of event is an addition here plus a name in {@link EventNames} -- no
 * other part of the channel has to change for it.
 */
public final class EventDtos {

	private EventDtos() {
	}

	/** The SSE {@code event:} names. The name is the event's type; the data carries none. */
	public static final class EventNames {

		/** {@link ProjectViewState}. */
		public static final String PROJECT_VIEW_STATE = "project-view-state";

		/** {@link ProjectCatalog}. */
		public static final String PROJECT_CATALOG = "project-catalog";

		private EventNames() {
		}
	}

	/**
	 * A project's working state -- active layer, per-layer sort, query and selection --
	 * now stands at {@code version}. Read it from
	 * {@code GET /api/projects/{projectId}/view-state}.
	 *
	 * @param version rises with every write to that project's working state
	 * @param origin  the {@code X-Hgis-Client} of whoever wrote it, or null when they
	 *     named none. A client that finds its own name here already holds this state and
	 *     has nothing to read -- which is what keeps a write from bouncing back as a read
	 *     that provokes the next write.
	 */
	public record ProjectViewState(UUID projectId, long version, String origin) {
	}

	/**
	 * A project's catalog -- its layer list, a layer's properties, its style, its data --
	 * now stands at {@code version}. Read it from
	 * {@code GET /api/projects/{projectId}/layers}: the layer summaries that comes back
	 * carry {@code dataVersion}, {@code styleVersion} and {@code clipVersion} individually,
	 * so re-reading that list is enough to notice and refetch whatever tiles moved.
	 *
	 * @param version rises with every write to any layer or field of this project.
	 *     Unlike {@link ProjectViewState#version}, moved by a database trigger rather than
	 *     the write path that caused it -- see {@code CatalogChanged}'s own javadoc for why.
	 * @param origin  same rule as {@link ProjectViewState#origin}.
	 */
	public record ProjectCatalog(UUID projectId, long version, String origin) {
	}
}
