/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
package com.sun.max.ins.memory;

import com.sun.max.collect.*;

/**
 * Defines the columns that can be displayed describing a region of memory word values in the VM.
 *
 * @author Michael Van De Vanter
 */
public enum MemoryWordsColumnKind {
    TAG("Tag", "Additional information", true, -1),
    ADDRESS("Addr.", "Memory address", true, -1),
    POSITION("Pos.", "Relative position (bytes)", true, 20),
    VALUE("Value", "Word value", true, 20),
    REGION("Region", "Memory region pointed to by value", true, 20);

    private final String columnLabel;
    private final String toolTipText;
    private final boolean defaultVisibility;
    private final int minWidth;

    private MemoryWordsColumnKind(String label, String toolTipText, boolean defaultVisibility, int minWidth) {
        this.columnLabel = label;
        this.toolTipText = toolTipText;
        this.defaultVisibility = defaultVisibility;
        this.minWidth = minWidth;
    }

    /**
     * @return text to appear in the column header
     */
    public String label() {
        return columnLabel;
    }

    /**
     * @return text to appear in the column header's toolTip, null if none specified
     */
    public String toolTipText() {
        return toolTipText;
    }

    /**
     * @return minimum width allowed for this column when resized by user; -1 if none specified.
     */
    public int minWidth() {
        return minWidth;
    }

    @Override
    public String toString() {
        return columnLabel;
    }

    /**
     * @return whether this column kind can be made invisible; default true.
     */
    public boolean canBeMadeInvisible() {
        return true;
    }

    /**
     * Determines if this column should be visible by default; default true.
     */
    public boolean defaultVisibility() {
        return defaultVisibility;
    }

    public static final IndexedSequence<MemoryWordsColumnKind> VALUES = new ArraySequence<MemoryWordsColumnKind>(values());

}
