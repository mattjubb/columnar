package io.columnar.expr;

/**
 * Marker interface attached via ByteBuddy to generated subclasses — proves the codegen
 * pipeline wired a subclass loader without altering predicate semantics.
 */
public interface BuddyMarker {}
