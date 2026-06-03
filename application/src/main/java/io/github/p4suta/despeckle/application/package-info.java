/**
 * The despeckle application layer: the orchestration services that drive a run through the {@code
 * port} interfaces. {@link io.github.p4suta.despeckle.application.DespeckleService} walks an image
 * corpus, {@link io.github.p4suta.despeckle.application.PdfPipelineService} drives one
 * PDF&nbsp;&rarr; PDF book, {@link io.github.p4suta.despeckle.application.PdfBatchService} fans the
 * pipeline over a directory of books, and {@link
 * io.github.p4suta.despeckle.application.Jbig2PackService} packs a directory of cleaned pages into
 * a PDF. These classes own the filesystem walk, the worker pools and the temp-directory lifecycle,
 * but every despeckle, extract, assemble, linearize or report step is reached only through a {@code
 * port}; the concrete adapters are injected by the {@code :app} composition root. The layer depends
 * solely on {@code domain.model}, {@code domain.service}, {@code port} and SLF4J — never on {@code
 * :infrastructure}, PDFBox, AWT or the FFM island.
 */
@NullMarked
package io.github.p4suta.despeckle.application;

import org.jspecify.annotations.NullMarked;
