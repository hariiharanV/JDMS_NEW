package com.jmr.jdms.shared.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * The paging envelope of every list endpoint ({@code docs/04-api-outline.md}): {@code total} counts only items the
 * caller may see.
 */
public record PageResponse<T>(List<T> items, int page, int size, long total) {

    public static final int MAX_SIZE = 200;

    public static <S, T> PageResponse<T> of(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements());
    }
}
