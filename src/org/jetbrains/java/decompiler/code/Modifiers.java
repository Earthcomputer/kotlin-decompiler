package org.jetbrains.java.decompiler.code;

import kotlinx.metadata.Flag;
import kotlinx.metadata.FlagsKt;
import org.jetbrains.java.decompiler.util.TextBuffer;

import java.util.Collections;
import java.util.EnumSet;

public enum Modifiers {
  _HAS_ANNOTATIONS(null, Flag.HAS_ANNOTATIONS, Type.HAS_ANNOTATIONS),
  INTERNAL("internal", Flag.IS_INTERNAL, Type.VISIBILITY),
  PRIVATE("private", Flag.IS_PRIVATE, Type.VISIBILITY),
  PROTECTED("protected", Flag.IS_PROTECTED, Type.VISIBILITY),
  PUBLIC("public", Flag.IS_PUBLIC, Type.VISIBILITY),
  _PRIVATE_TO_THIS(null, Flag.IS_PRIVATE_TO_THIS, Type.VISIBILITY),
  LOCAL("local", Flag.IS_LOCAL, Type.VISIBILITY),
  FINAL("final", Flag.IS_FINAL, Type.MODALITY),
  OPEN("open", Flag.IS_OPEN, Type.MODALITY),
  ABSTRACT("abstract", Flag.IS_ABSTRACT, Type.MODALITY),
  SEALED("sealed", Flag.IS_SEALED, Type.MODALITY),

  CLASS("class", Flag.Class.IS_CLASS, Type.CLASS),
  INTERFACE("interface", Flag.Class.IS_INTERFACE, Type.CLASS),
  ENUM_CLASS("enum class", Flag.Class.IS_ENUM_CLASS, Type.CLASS),
  _ENUM_ENTRY(null, Flag.Class.IS_ENUM_ENTRY, Type.CLASS),
  ANNOTATION_CLASS("annotation class", Flag.Class.IS_ANNOTATION_CLASS, Type.CLASS),
  OBJECT("object", Flag.Class.IS_OBJECT, Type.CLASS),
  COMPANION_OBJECT("companion object", Flag.Class.IS_COMPANION_OBJECT, Type.CLASS),
  INNER("inner", Flag.Class.IS_INNER, Type.CLASS),
  DATA("data", Flag.Class.IS_DATA, Type.CLASS),
  CLASS_EXTERNAL("external", Flag.Class.IS_EXTERNAL, Type.CLASS),
  CLASS_EXPECT("expect", Flag.Class.IS_EXPECT, Type.CLASS),
  VALUE("value", Flag.Class.IS_VALUE, Type.CLASS),
  FUN("fun", Flag.Class.IS_FUN, Type.CLASS),

  _SECONDARY(null, Flag.Constructor.IS_SECONDARY, Type.CONSTRUCTOR),
  _CONSTRUCTOR_HAS_NON_STABLE_PARAMETER_NAMES(null, Flag.Constructor.HAS_NON_STABLE_PARAMETER_NAMES, Type.CONSTRUCTOR),

  _FUNCTION_DECLARATION(null, Flag.Function.IS_DECLARATION, Type.FUNCTION),
  _FUNCTION_FAKE_OVERRIDE(null, Flag.Function.IS_FAKE_OVERRIDE, Type.FUNCTION),
  _FUNCTION_DELEGATION(null, Flag.Function.IS_DELEGATION, Type.FUNCTION),
  _FUNCTION_SYNTHESIZED(null, Flag.Function.IS_SYNTHESIZED, Type.FUNCTION),
  OPERATOR("operator", Flag.Function.IS_OPERATOR, Type.FUNCTION),
  INFIX("infix", Flag.Function.IS_INFIX, Type.FUNCTION),
  FUNCTION_INLINE("inline", Flag.Function.IS_INLINE, Type.FUNCTION),
  TAILREC("tailrec", Flag.Function.IS_TAILREC, Type.FUNCTION),
  FUNCTION_EXTERNAL("external", Flag.Function.IS_EXTERNAL, Type.FUNCTION),
  FUNCTION_SUSPEND("suspend", Flag.Function.IS_SUSPEND, Type.FUNCTION),
  FUNCTION_EXPECT("expect", Flag.Function.IS_EXPECT, Type.FUNCTION),
  _FUNCTION_HAS_NON_STABLE_PARAMETER_NAMES(null, Flag.Function.HAS_NON_STABLE_PARAMETER_NAMES, Type.FUNCTION),

  _PROPERTY_DECLARATION(null, Flag.Property.IS_DECLARATION, Type.PROPERTY),
  _PROPERTY_FAKE_OVERRIDE(null, Flag.Property.IS_FAKE_OVERRIDE, Type.PROPERTY),
  _PROPERTY_DELEGATION(null, Flag.Property.IS_DECLARATION, Type.PROPERTY),
  _PROPERTY_SYNTHESIZED(null, Flag.Property.IS_SYNTHESIZED, Type.PROPERTY),
  _VAR(null, Flag.Property.IS_VAR, Type.PROPERTY),
  _HAS_GETTER(null, Flag.Property.HAS_GETTER, Type.PROPERTY),
  _HAS_SETTER(null, Flag.Property.HAS_SETTER, Type.PROPERTY),
  CONST("const", Flag.Property.IS_CONST, Type.PROPERTY),
  LATEINIT("lateinit", Flag.Property.IS_LATEINIT, Type.PROPERTY),
  _HAS_CONSTANT(null, Flag.Property.HAS_CONSTANT, Type.PROPERTY),
  PROPERTY_EXTERNAL("external", Flag.Property.IS_EXTERNAL, Type.PROPERTY),
  _PROPERTY_DELEGATED(null, Flag.Property.IS_DELEGATED, Type.PROPERTY),
  PROPERTY_EXPECT("expect", Flag.Property.IS_EXPECT, Type.PROPERTY),

  _IS_NOT_DEFAULT(null, Flag.PropertyAccessor.IS_NOT_DEFAULT, Type.PROPERTY_ACCESSOR),
  PROPERTY_ACCESSOR_EXTERNAL("external", Flag.PropertyAccessor.IS_EXTERNAL, Type.PROPERTY_ACCESSOR),
  PROPERTY_ACCESSOR_INLINE("inline", Flag.PropertyAccessor.IS_INLINE, Type.PROPERTY_ACCESSOR),

  _NULLABLE(null, Flag.Type.IS_NULLABLE, Type.TYPE),
  TYPE_SUSPEND("suspend", Flag.Type.IS_SUSPEND, Type.TYPE),

  REIFIED("reified", Flag.TypeParameter.IS_REIFIED, Type.TYPE_PARAMETER),

  _DECLARES_DEFAULT_VALUE(null, Flag.ValueParameter.DECLARES_DEFAULT_VALUE, Type.VALUE_PARAMETER),
  CROSSINLINE("crossinline", Flag.ValueParameter.IS_CROSSINLINE, Type.VALUE_PARAMETER),
  NOINLINE("noinline", Flag.ValueParameter.IS_NOINLINE, Type.VALUE_PARAMETER),

  _NEGATED(null, Flag.EffectExpression.IS_NEGATED, Type.EFFECT_EXPRESSION),
  _NULL_CHECK_PREDICATE(null, Flag.EffectExpression.IS_NULL_CHECK_PREDICATE, Type.EFFECT_EXPRESSION),
  ;

  private static final Modifiers[] ALL_MODIFIERS = values();

  private final String java;
  private final Flag kmFlag;
  private final int intFlag;
  private final Type type;
  Modifiers(String java, Flag kmFlag, Type type) {
    this.java = java;
    this.kmFlag = kmFlag;
    this.intFlag = FlagsKt.flagsOf(kmFlag);
    this.type = type;
  }

  public boolean test(int flags) {
    return (flags & intFlag) != 0;
  }

  public int add(int flags) {
    return flags | intFlag;
  }

  public int remove(int flags) {
    return flags & ~intFlag;
  }

  public int set(int flags, boolean value) {
    if (value) {
      return add(flags);
    } else {
      return remove(flags);
    }
  }

  public String toJava() {
    return java;
  }

  public Flag getKmFlag() {
    return kmFlag;
  }

  public Type getType() {
    return type;
  }

  public enum Type {
    HAS_ANNOTATIONS,
    VISIBILITY,
    MODALITY,
    CLASS(HAS_ANNOTATIONS, VISIBILITY, MODALITY),
    CONSTRUCTOR(HAS_ANNOTATIONS, VISIBILITY),
    FUNCTION(HAS_ANNOTATIONS, VISIBILITY, MODALITY),
    PROPERTY(HAS_ANNOTATIONS, VISIBILITY, MODALITY),
    PROPERTY_ACCESSOR(HAS_ANNOTATIONS, VISIBILITY, MODALITY),
    TYPE,
    TYPE_PARAMETER,
    VALUE_PARAMETER,
    EFFECT_EXPRESSION,
    ;

    private final Type[] canOwnTypes;
    Type(Type... canOwnTypes) {
      EnumSet<Type> canOwnTypesSet = EnumSet.of(this);
      for (Type canOwnType : canOwnTypes) {
        Collections.addAll(canOwnTypesSet, canOwnType.canOwnTypes);
      }
      this.canOwnTypes = canOwnTypesSet.toArray(new Type[0]);
    }

    private boolean canOwnType(Type type) {
      for (Type canOwnType : canOwnTypes) {
        if (canOwnType == type) {
          return true;
        }
      }
      return false;
    }

    public boolean toJava(int flags, TextBuffer buffer) {
      boolean hadModifier = false;
      for (Modifiers mod : ALL_MODIFIERS) {
        if (mod.test(flags) && canOwnType(mod.type) && mod.java != null) {
          if (hadModifier) {
            buffer.append(' ');
          }
          hadModifier = true;
          buffer.append(mod.java);
        }
      }
      return hadModifier;
    }

    public String toDebugString(int flags) {
      StringBuilder sb = new StringBuilder();

      for (Modifiers mod : ALL_MODIFIERS) {
        if (canOwnType(mod.type)) {
          if (sb.length() != 0) {
            sb.append(' ');
          }
          sb.append(mod.name());
        }
      }

      return sb.toString();
    }
  }
}
