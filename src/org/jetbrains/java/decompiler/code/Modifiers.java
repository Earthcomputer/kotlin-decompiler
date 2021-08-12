package org.jetbrains.java.decompiler.code;

import java.util.Locale;

public enum Modifiers {
  PRIVATE,
  PROTECTED,
  PUBLIC,
  ACTUAL,
  ABSTRACT,
  CONST,
  CROSSINLINE,
  EXPECT,
  EXTERNAL,
  FINAL,
  INFIX,
  INLINE,
  INNER,
  INTERNAL,
  LATEINIT,
  NOINLINE,
  OPEN,
  OPERATOR,
  SUSPEND,
  TAILREC,
  ;
  private final String name;
  private static final Modifiers[] ALL = values();
  Modifiers() {
    name = name().toLowerCase(Locale.ROOT);
  }
  public String getName() {
    return name;
  }
  public static Modifiers[] all() {
    return ALL; // no copy
  }
}
