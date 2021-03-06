/*
 * GRAKN.AI - THE KNOWLEDGE GRAPH
 * Copyright (C) 2019 Grakn Labs Ltd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package grakn.core.graph.core;

import grakn.core.graph.core.JanusGraphException;
import grakn.core.graph.core.JanusGraphQuery;
import grakn.core.graph.core.JanusGraphVertex;

/**
 * Exception thrown when a user defined query (e.g. a {@link JanusGraphVertex} or {@link JanusGraphQuery})
 * is invalid or could not be processed.
 */
public class QueryException extends JanusGraphException {

    private static final long serialVersionUID = 1L;

    /**
     * @param msg Exception message
     */
    public QueryException(String msg) {
        super(msg);
    }

    /**
     * @param msg   Exception message
     * @param cause Cause of the exception
     */
    public QueryException(String msg, Throwable cause) {
        super(msg, cause);
    }

    /**
     * Constructs an exception with a generic message
     *
     * @param cause Cause of the exception
     */
    public QueryException(Throwable cause) {
        this("Exception in query.", cause);
    }

}
