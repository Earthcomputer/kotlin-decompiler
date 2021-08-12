package org.jetbrains.java.decompiler.code;

import org.jetbrains.java.decompiler.util.TextBuffer;

import java.util.BitSet;

public final class ModifierList {
  private final BitSet modifiers = new BitSet();

  public void add(Modifiers modifier) {
    modifiers.set(modifier.ordinal());
  }

  public void remove(Modifiers modifier) {
    modifiers.clear(modifier.ordinal());
  }

  public boolean has(Modifiers modifier) {
    return modifiers.get(modifier.ordinal());
  }

  @Override
  public int hashCode() {
    return modifiers.hashCode();
  }

  @Override
  public boolean equals(Object other) {
    return (other instanceof ModifierList) && modifiers.equals(((ModifierList) other).modifiers);
  }

  public String toString() {
    TextBuffer buf = new TextBuffer();
    toJava(buf);
    return buf.toString();
  }

  public boolean toJava(TextBuffer buf) {
    boolean addedModifiers = false;
    for (int i = modifiers.nextSetBit(0); i >= 0; i = modifiers.nextSetBit(i+1)) {
      if (addedModifiers) {
        buf.append(' ');
      }
      addedModifiers = true;
      buf.append(Modifiers.all()[i].getName());
    }
    return addedModifiers;
  }

  public static ModifierList fromAccessFlags(int accessFlags) {
    ModifierList lst = new ModifierList();
    //if ((accessFlags & CodeConstants.ACC_PUBLIC) != 0) lst.add(Modifiers.PUBLIC);
    if ((accessFlags & CodeConstants.ACC_PROTECTED) != 0) lst.add(Modifiers.PROTECTED);
    if ((accessFlags & CodeConstants.ACC_PRIVATE) != 0) lst.add(Modifiers.PRIVATE);
    if ((accessFlags & CodeConstants.ACC_ABSTRACT) != 0) lst.add(Modifiers.ABSTRACT);
    if ((accessFlags & CodeConstants.ACC_FINAL) != 0) lst.add(Modifiers.FINAL);
    if ((accessFlags & CodeConstants.ACC_NATIVE) != 0) lst.add(Modifiers.EXTERNAL);
    return lst;
  }
}
