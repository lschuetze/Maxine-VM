/*
 * Copyright (c) 2007, 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.sun.max.ins.gui;

/**
 * A marker interface for an {@link Inspector} that contains other inspectors.
 * The contained inspectors have the container as their parent.
  *
 * @author Mick Jordan
 * @author Doug Simon
 * @author Michael Van De Vanter
 */
public interface InspectorContainer<Member_Type extends Inspector> extends Iterable<Member_Type> {

    int length();

    Member_Type inspectorAt(int i);

    /**
     * Ensures that the inspector is visible and selected.
     */
    void setSelected(Member_Type inspector);

    boolean isSelected(Member_Type inspector);

    Member_Type getSelected();

    int getSelectedIndex();
}
