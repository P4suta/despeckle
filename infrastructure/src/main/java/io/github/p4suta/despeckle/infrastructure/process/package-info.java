/**
 * Shared process-execution utilities for the infrastructure adapters: resolving the external native
 * tools ({@code pdfimages}, {@code pdfinfo}, {@code jbig2}, {@code qpdf}) via {@code
 * -Ddespeckle.<tool>.path} then {@code PATH}, and launching them with output capture, exit-code
 * inspection and timeouts. This is the shared "how" of shelling out to a {@link java.util.List} of
 * command-line arguments, not a domain intent, so it stays an infrastructure-internal helper rather
 * than a port.
 */
@NullMarked
package io.github.p4suta.despeckle.infrastructure.process;

import org.jspecify.annotations.NullMarked;
