package io.reladynamo.ddb.differential.findermatrix;

import com.gs.fw.common.mithra.finder.orderby.OrderBy;

/** Public finder shape for one invocation. OrderBy/max are applied before resolution. */
final class FinderShape {

    enum Kind {
        FIND_MANY,
        FIND_ONE,
        FIND_ONE_BYPASS,
        COUNT
    }

    enum Route {
        QUERY_GSI,
        QUERY_BASE,
        GET_ITEM,
        EXECUTE_OR_FANOUT
    }

    final Kind kind;
    final OrderBy orderBy;
    final Integer max;
    final Route route;
    final boolean ordered;

    FinderShape(Kind kind, OrderBy orderBy, Integer max, Route route, boolean ordered) {
        this.kind = kind;
        this.orderBy = orderBy;
        this.route = route;
        this.max = max;
        this.ordered = ordered;
    }

    static FinderShape findManyQuery() {
        return new FinderShape(Kind.FIND_MANY, null, null, Route.QUERY_GSI, false);
    }

    static FinderShape findManyQueryBase() {
        return new FinderShape(Kind.FIND_MANY, null, null, Route.QUERY_BASE, false);
    }

    static FinderShape findManyOrdered(OrderBy orderBy, Integer max, Route route) {
        return new FinderShape(Kind.FIND_MANY, orderBy, max, route, true);
    }

    static FinderShape findManyGetItem() {
        return new FinderShape(Kind.FIND_MANY, null, null, Route.GET_ITEM, false);
    }

    static FinderShape findManyFanout() {
        return new FinderShape(Kind.FIND_MANY, null, null, Route.EXECUTE_OR_FANOUT, false);
    }

    static FinderShape findOneGetItem() {
        return new FinderShape(Kind.FIND_ONE, null, null, Route.GET_ITEM, false);
    }

    static FinderShape findOneQuery() {
        return new FinderShape(Kind.FIND_ONE, null, null, Route.QUERY_BASE, false);
    }

    static FinderShape findOneBypassGetItem() {
        return new FinderShape(Kind.FIND_ONE_BYPASS, null, null, Route.GET_ITEM, false);
    }

    static FinderShape countQuery() {
        return new FinderShape(Kind.COUNT, null, null, Route.QUERY_GSI, false);
    }

    static FinderShape countQueryBase() {
        return new FinderShape(Kind.COUNT, null, null, Route.QUERY_BASE, false);
    }

    static FinderShape countGetItem() {
        return new FinderShape(Kind.COUNT, null, null, Route.GET_ITEM, false);
    }

    static FinderShape countFanout() {
        return new FinderShape(Kind.COUNT, null, null, Route.EXECUTE_OR_FANOUT, false);
    }

    @Override
    public String toString() {
        return kind + (max == null ? "" : " max=" + max) + " route=" + route;
    }
}
