package io.github.p4suta.despeckle.domain.model;

/**
 * One book's line in the batch index — the domain value the batch reporter renders. Replaces the
 * prior {@code BatchIndex.Book} record, with the status carried as a {@link BookStatus} enum rather
 * than a free-form string.
 *
 * @param name the source PDF's file name (display label)
 * @param stem the name without its {@code .pdf} extension (the per-book report sub-directory)
 * @param status whether the book was cleaned, skipped or failed
 * @param pages pages cleaned (meaningful only when {@link BookStatus#OK})
 * @param componentsRemoved specks removed (meaningful only when {@link BookStatus#OK})
 * @param hasReport whether a per-book {@code <stem>/index.html} exists to link to
 */
public record BatchBook(
        String name,
        String stem,
        BookStatus status,
        int pages,
        long componentsRemoved,
        boolean hasReport) {}
