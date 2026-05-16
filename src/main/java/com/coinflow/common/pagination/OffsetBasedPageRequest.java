package com.coinflow.common.pagination;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public record OffsetBasedPageRequest(long offset, int limit) implements Pageable {

    @Override public int getPageNumber()  { return (int) (offset / limit); }
    @Override public int getPageSize()    { return limit; }
    @Override public long getOffset()     { return offset; }
    @Override public Sort getSort()       { return Sort.unsorted(); }

    @Override
    public Pageable next() {
        return new OffsetBasedPageRequest(offset + limit, limit);
    }

    @Override
    public Pageable previousOrFirst() {
        return offset <= 0 ? this : new OffsetBasedPageRequest(Math.max(0, offset - limit), limit);
    }

    @Override
    public Pageable first() {
        return new OffsetBasedPageRequest(0, limit);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetBasedPageRequest((long) pageNumber * limit, limit);
    }

    @Override
    public boolean hasPrevious() {
        return offset > 0;
    }
}
