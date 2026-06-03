/**
 * The Leptonica adapter: the single unsafe island where the Foreign Function &amp; Memory API binds
 * to the system Leptonica library. {@link io.github.p4suta.despeckle.infrastructure.leptonica.Pix}
 * is the owning RAII handle, {@link io.github.p4suta.despeckle.infrastructure.leptonica.Leptonica}
 * holds the FFM downcalls and {@code IFF_*} constants, and {@link
 * io.github.p4suta.despeckle.infrastructure.leptonica.LeptonicaPageCleaner} implements the {@link
 * io.github.p4suta.despeckle.port.PageCleaner} port. No {@code Pix} or FFM type crosses this
 * boundary.
 */
@NullMarked
package io.github.p4suta.despeckle.infrastructure.leptonica;

import org.jspecify.annotations.NullMarked;
