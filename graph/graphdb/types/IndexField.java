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

package grakn.core.graph.graphdb.types;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import grakn.core.graph.core.PropertyKey;


public class IndexField {

    private final PropertyKey key;

    IndexField(PropertyKey key) {
        this.key = Preconditions.checkNotNull(key);
    }

    public PropertyKey getFieldKey() {
        return key;
    }

    public static IndexField of(PropertyKey key) {
        return new IndexField(key);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(key);
    }

    @Override
    public boolean equals(Object oth) {
        if (this == oth) {
            return true;
        } else if (!getClass().isInstance(oth)) {
            return false;
        }
        IndexField other = (IndexField) oth;
        if (key == null) return key == other.key;
        else return key.equals(other.key);
    }

    @Override
    public String toString() {
        return "[" + key.name() + "]";
    }

}
