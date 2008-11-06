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
package com.sun.max.vm.template.source;

import com.sun.max.annotate.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.builtin.*;
import com.sun.max.vm.reference.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.stack.*;
import com.sun.max.vm.type.*;

/**
 * This class offers stack frame operations for use in bytecode templates, including
 * pushing and popping values from the operand stack, accessing local variables, and
 * making Java calls.
 *
 * This class assumes that the ABI stack pointer is used as the java stack pointer,
 * and when these methods are inlined into bytecode templates, they result in code that
 * indexes directly off of the ABI stack pointer, which points into the java operand
 * stack. Similarly, this class assumes the ABI frame pointer is used to access the
 * local variables.
 *
 * @author Laurent Daynes
 * @author Bernd Mathiske
 * @author Ben L. Titzer
 * @author Michael Bebenita
 */
public final class JitStackFrameOperation {
    private JitStackFrameOperation() {
    }

    private static final int SLOT_WORDS = JitStackFrameLayout.JIT_SLOT_SIZE / Word.size();
    private static final int BIAS = JitStackFrameLayout.JIT_STACK_BIAS;

    private static final int HALFWORD_OFFSET_IN_WORD = JitStackFrameLayout.offsetWithinWord(Kind.INT);

    @INLINE
    static void addSlots(int numberOfSlots) {
        VMRegister.addWordsToAbiStackPointer(-(numberOfSlots * SLOT_WORDS));
    }

    @INLINE
    static void removeSlots(int numberOfSlots) {
        VMRegister.addWordsToAbiStackPointer(numberOfSlots * SLOT_WORDS);
    }

    @INLINE
    static Word peekWord(int index) {
        return VMRegister.getAbiStackPointer().getWord(BIAS, index * SLOT_WORDS);
    }

    @INLINE
    static int peekInt(int index) {
        return VMRegister.getAbiStackPointer().readInt(BIAS + index * JitStackFrameLayout.JIT_SLOT_SIZE + HALFWORD_OFFSET_IN_WORD);
    }

    @INLINE
    static float peekFloat(int index) {
        return VMRegister.getAbiStackPointer().readFloat(BIAS + index * JitStackFrameLayout.JIT_SLOT_SIZE + HALFWORD_OFFSET_IN_WORD);
    }

    @INLINE
    static long peekLong(int index) {
        return VMRegister.getAbiStackPointer().readLong(BIAS + index * JitStackFrameLayout.JIT_SLOT_SIZE);
    }

    @INLINE
    static double peekDouble(int index) {
        return VMRegister.getAbiStackPointer().readDouble(BIAS + index * JitStackFrameLayout.JIT_SLOT_SIZE);
    }

    @INLINE
    static Object peekReference(int index) {
        return VMRegister.getAbiStackPointer().getReference(BIAS, index * SLOT_WORDS).toJava();
    }

    @INLINE
    static void pokeWord(int index, Word value) {
        VMRegister.getAbiStackPointer().setWord(BIAS, index * SLOT_WORDS, value);
    }

    @INLINE
    public static void pokeInt(int index, int value) {
        VMRegister.getAbiStackPointer().writeInt(BIAS + index * JitStackFrameLayout.JIT_SLOT_SIZE + HALFWORD_OFFSET_IN_WORD, value);
    }

    @INLINE
    static void pokeFloat(int index, float value) {
        VMRegister.getAbiStackPointer().writeFloat(BIAS + index * JitStackFrameLayout.JIT_SLOT_SIZE + HALFWORD_OFFSET_IN_WORD, value);
    }

    @INLINE
    static void pokeLong(int index, long value) {
        VMRegister.getAbiStackPointer().writeLong(BIAS + index * JitStackFrameLayout.JIT_SLOT_SIZE, value);
    }

    @INLINE
    static void pokeDouble(int index, double value) {
        VMRegister.getAbiStackPointer().writeDouble(BIAS + index * JitStackFrameLayout.JIT_SLOT_SIZE, value);
    }

    @INLINE
    static void pokeReference(int index, Object value) {
        VMRegister.getAbiStackPointer().setReference(BIAS, index * SLOT_WORDS, Reference.fromJava(value));
    }

    @INLINE
    static float popFloat() {
        final float value = peekFloat(0);
        removeSlots(1);
        return value;
    }

    @INLINE
    static int popInt() {
        final int value = peekInt(0);
        removeSlots(1);
        return value;
    }

    @INLINE
    static long popLong() {
        final long value = peekLong(0);
        removeSlots(2);
        return value;
    }

    @INLINE
    static double popDouble() {
        final double value = peekDouble(0);
        removeSlots(2);
        return value;
    }

    @INLINE
    static Object popReference() {
        final Object value = peekReference(0);
        removeSlots(1);
        return value;
    }

    @INLINE
    static void pushWord(final Word value) {
        addSlots(1);
        pokeWord(0, value);
    }


    @INLINE
    static void pushFloat(final float value) {
        addSlots(1);
        pokeFloat(0, value);
    }

    @INLINE
    static void pushInt(final int value) {
        addSlots(1);
        pokeInt(0, value);
    }

    @INLINE
    static void pushReference(final Object value) {
        addSlots(1);
        pokeReference(0, value);
    }

    @INLINE
    static void pushLong(final long value) {
        addSlots(2);
        pokeLong(0, value);
    }

    @INLINE
    static void pushDouble(final double value) {
        addSlots(2);
        pokeDouble(0, value);
    }

    @INLINE
    static void setLocalReference(int slotOffset, Object value) {
        VMRegister.getAbiFramePointer().writeReference(slotOffset, Reference.fromJava(value));
    }

    @INLINE
    static void setLocalInt(int slotOffset, int value) {
        VMRegister.getAbiFramePointer().writeInt(slotOffset, value);
    }

    @INLINE
    static void setLocalFloat(int slotOffset, float value) {
        VMRegister.getAbiFramePointer().writeFloat(slotOffset, value);
    }

    @INLINE
    static void setLocalLong(int slotOffset, long value) {
        VMRegister.getAbiFramePointer().writeLong(slotOffset, value);
    }

    @INLINE
    static void setLocalDouble(int slotOffset, double value) {
        VMRegister.getAbiFramePointer().writeDouble(slotOffset, value);
    }

    @INLINE
    static Object getLocalReference(int slotOffset) {
        return VMRegister.getAbiFramePointer().readReference(slotOffset).toJava();
    }

    @INLINE
    static int getLocalInt(int slotOffset) {
        return VMRegister.getAbiFramePointer().readInt(slotOffset);
    }

    @INLINE
    static float getLocalFloat(int slotOffset) {
        return VMRegister.getAbiFramePointer().readFloat(slotOffset);
    }

    @INLINE
    static long getLocalLong(int slotOffset) {
        return VMRegister.getAbiFramePointer().readLong(slotOffset);
    }

    @INLINE
    static double getLocalDouble(int slotOffset) {
        return VMRegister.getAbiFramePointer().readDouble(slotOffset);
    }

    @INLINE
    static void directCallVoid() {
        SpecialBuiltin.call();
    }

    @INLINE
    static void directCallFloat() {
        final float result = SpecialBuiltin.callFloat();
        SpecialBuiltin.mark();
        pushFloat(result);
    }

    @INLINE
    static void directCallLong() {
        final long result = SpecialBuiltin.callLong();
        SpecialBuiltin.mark();
        pushLong(result);
    }

    @INLINE
    static void directCallDouble() {
        final double result = SpecialBuiltin.callDouble();
        SpecialBuiltin.mark();
        pushDouble(result);
    }

    @INLINE
    static void directCallWord() {
        final Word result = SpecialBuiltin.callWord();
        SpecialBuiltin.mark();
        pushWord(result);
    }

    @INLINE
    static void indirectCallVoid(Address address, CallEntryPoint callEntryPoint) {
        SpecialBuiltin.call(address.plus(CallEntryPoint.JIT_ENTRY_POINT.offsetFromCodeStart() - callEntryPoint.offsetFromCodeStart()));
    }

    @INLINE
    static void indirectCallFloat(Address address, CallEntryPoint callEntryPoint) {
        final float result = SpecialBuiltin.callFloat(address.plus(CallEntryPoint.JIT_ENTRY_POINT.offsetFromCodeStart() - callEntryPoint.offsetFromCodeStart()));
        SpecialBuiltin.mark();
        pushFloat(result);
    }

    @INLINE
    static void indirectCallLong(Address address, CallEntryPoint callEntryPoint) {
        final long result = SpecialBuiltin.callLong(address.plus(CallEntryPoint.JIT_ENTRY_POINT.offsetFromCodeStart() - callEntryPoint.offsetFromCodeStart()));
        SpecialBuiltin.mark();
        pushLong(result);
    }

    @INLINE
    static void indirectCallDouble(Address address, CallEntryPoint callEntryPoint) {
        final double result = SpecialBuiltin.callDouble(address.plus(CallEntryPoint.JIT_ENTRY_POINT.offsetFromCodeStart() - callEntryPoint.offsetFromCodeStart()));
        SpecialBuiltin.mark();
        pushDouble(result);
    }

    @INLINE
    static void indirectCallWord(Address address, CallEntryPoint callEntryPoint) {
        final Word result = SpecialBuiltin.callWord(address.plus(CallEntryPoint.JIT_ENTRY_POINT.offsetFromCodeStart() - callEntryPoint.offsetFromCodeStart()));
        SpecialBuiltin.mark();
        pushWord(result);
    }

    @INLINE
    static void indirectCallVoid(Address address, CallEntryPoint callEntryPoint, Object receiver) {
        SpecialBuiltin.call(address.plus(CallEntryPoint.JIT_ENTRY_POINT.offsetFromCodeStart() - callEntryPoint.offsetFromCodeStart()), receiver);
    }

    @INLINE
    static void indirectCallFloat(Address address, CallEntryPoint callEntryPoint, Object receiver) {
        final float result = SpecialBuiltin.callFloat(address.plus(CallEntryPoint.JIT_ENTRY_POINT.offsetFromCodeStart() - callEntryPoint.offsetFromCodeStart()), receiver);
        SpecialBuiltin.mark();
        pushFloat(result);
    }

    @INLINE
    static void indirectCallLong(Address address, CallEntryPoint callEntryPoint, Object receiver) {
        final long result = SpecialBuiltin.callLong(address.plus(CallEntryPoint.JIT_ENTRY_POINT.offsetFromCodeStart() - callEntryPoint.offsetFromCodeStart()), receiver);
        pushLong(result);
    }

    @INLINE
    static void indirectCallDouble(Address address, CallEntryPoint callEntryPoint, Object receiver) {
        final double result = SpecialBuiltin.callDouble(address.plus(CallEntryPoint.JIT_ENTRY_POINT.offsetFromCodeStart() - callEntryPoint.offsetFromCodeStart()), receiver);
        pushDouble(result);
    }

    @INLINE
    static void indirectCallWord(Address address, CallEntryPoint callEntryPoint, Object receiver) {
        final Word result = SpecialBuiltin.callWord(address.plus(CallEntryPoint.JIT_ENTRY_POINT.offsetFromCodeStart() - callEntryPoint.offsetFromCodeStart()), receiver);
        pushWord(result);
    }
}
