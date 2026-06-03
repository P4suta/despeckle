/**
 * The despeckle ports: the narrow interfaces the application layer drives and the infrastructure
 * layer implements. Every signature is expressed purely in terms of {@code domain.model} value
 * types and JDK types ({@link java.nio.file.Path}, primitives, {@link java.util.OptionalInt},
 * {@link java.util.List}, {@link java.util.concurrent.ExecutorService}, {@link
 * java.io.IOException}); no Leptonica {@code Pix}, PDFBox or AWT type ever crosses a port boundary,
 * so the dependency is kept unidirectional and the adapters stay swappable.
 */
@NullMarked
package io.github.p4suta.despeckle.port;

import org.jspecify.annotations.NullMarked;
