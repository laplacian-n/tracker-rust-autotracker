package org.opensourcephysics.media.core;

/**
 * Patched Tracker: the one switch between this build's changes and stock Tracker
 * 6.3.5 behaviour. It follows the AutoTracker's Fast checkbox: ticked = native
 * matcher with sub-pixel refinement, read-ahead decoding, ahead-of-time
 * matching and the lighter UI updates; unticked = the original code paths
 * (Java search loop, parabola fit, Tracker's own decoding and painting).
 */
public final class Patch {

	/** true while the patched code paths are in use; starts off, like the Fast checkbox */
	public static volatile boolean ON = Boolean.getBoolean("tracker.patch.on"); //$NON-NLS-1$

	private Patch() {
	}
}
