// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.java.decompiler.main;

import kotlinx.metadata.Flag;
import kotlinx.metadata.KmClass;
import kotlinx.metadata.KmClassifier;
import kotlinx.metadata.KmFunction;
import kotlinx.metadata.KmProperty;
import kotlinx.metadata.KmType;
import kotlinx.metadata.jvm.KotlinClassMetadata;
import net.fabricmc.fernflower.api.IFabricJavadocProvider;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.code.ModifierList;
import org.jetbrains.java.decompiler.code.Modifiers;
import org.jetbrains.java.decompiler.main.ClassesProcessor.ClassNode;
import org.jetbrains.java.decompiler.main.collectors.BytecodeMappingTracer;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.rels.ClassWrapper;
import org.jetbrains.java.decompiler.main.rels.MethodWrapper;
import org.jetbrains.java.decompiler.modules.decompiler.ExprProcessor;
import org.jetbrains.java.decompiler.modules.decompiler.exps.*;
import org.jetbrains.java.decompiler.modules.decompiler.stats.RootStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarTypeProcessor;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionPair;
import org.jetbrains.java.decompiler.modules.renamer.PoolInterceptor;
import org.jetbrains.java.decompiler.struct.*;
import org.jetbrains.java.decompiler.struct.attr.*;
import org.jetbrains.java.decompiler.struct.consts.PrimitiveConstant;
import org.jetbrains.java.decompiler.struct.gen.FieldDescriptor;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;
import org.jetbrains.java.decompiler.struct.gen.VarType;
import org.jetbrains.java.decompiler.struct.gen.generics.*;
import org.jetbrains.java.decompiler.util.InterpreterUtil;
import org.jetbrains.java.decompiler.util.TextBuffer;
import org.jetbrains.java.decompiler.util.VBStyleCollection;

import java.util.*;
import java.util.stream.Collectors;

public class ClassWriter {
  private final PoolInterceptor interceptor;
  private final IFabricJavadocProvider javadocProvider;

  public ClassWriter() {
    interceptor = DecompilerContext.getPoolInterceptor();
    javadocProvider = (IFabricJavadocProvider) DecompilerContext.getProperty(IFabricJavadocProvider.PROPERTY_NAME);
  }

  private static void invokeProcessors(ClassNode node) {
    // TODO: need to wrap around with try catch as failure here can break the entire class

    ClassWrapper wrapper = node.getWrapper();
    StructClass cl = wrapper.getClassStruct();

    InitializerProcessor.extractInitializers(wrapper);
    InitializerProcessor.hideInitalizers(wrapper);

    if (node.type == ClassNode.CLASS_ROOT &&
        !cl.isVersion5() &&
        DecompilerContext.getOption(IFernflowerPreferences.DECOMPILE_CLASS_1_4)) {
      ClassReference14Processor.processClassReferences(node);
    }

    if (cl.hasModifier(CodeConstants.ACC_ENUM) && DecompilerContext.getOption(IFernflowerPreferences.DECOMPILE_ENUM)) {
      EnumProcessor.clearEnum(wrapper);
    }

    if (DecompilerContext.getOption(IFernflowerPreferences.DECOMPILE_ASSERTIONS)) {
      AssertProcessor.buildAssertions(node);
    }
  }

  public void classLambdaToJava(ClassNode node, TextBuffer buffer, Exprent method_object, int indent, BytecodeMappingTracer origTracer) {
    ClassWrapper wrapper = node.getWrapper();
    if (wrapper == null) {
      return;
    }

    boolean lambdaToAnonymous = DecompilerContext.getOption(IFernflowerPreferences.LAMBDA_TO_ANONYMOUS_CLASS);

    ClassNode outerNode = (ClassNode)DecompilerContext.getProperty(DecompilerContext.CURRENT_CLASS_NODE);
    DecompilerContext.setProperty(DecompilerContext.CURRENT_CLASS_NODE, node);

    BytecodeMappingTracer tracer = new BytecodeMappingTracer(origTracer.getCurrentSourceLine());

    try {
      StructClass cl = wrapper.getClassStruct();

      DecompilerContext.getLogger().startWriteClass(node.simpleName);

      if (node.lambdaInformation.is_method_reference) {
        if (!node.lambdaInformation.is_content_method_static && method_object != null) {
          // reference to a virtual method
          method_object.getInferredExprType(new VarType(CodeConstants.TYPE_OBJECT, 0, node.lambdaInformation.content_class_name));
          String instance = method_object.toJava(indent, tracer).toString();
          // If the instance is casted, then we need to wrap it
          if (method_object.type == Exprent.EXPRENT_FUNCTION && ((FunctionExprent)method_object).getFuncType() == FunctionExprent.FUNCTION_CAST && ((FunctionExprent)method_object).doesCast()) {
            buffer.append('(').append(instance).append(')');
          }
          else {
            buffer.append(instance);
          }
        }
        else {
          // reference to a static method
          buffer.append(ExprProcessor.getCastTypeName(new VarType(node.lambdaInformation.content_class_name, true)));
        }

        buffer.append("::")
          .append(CodeConstants.INIT_NAME.equals(node.lambdaInformation.content_method_name) ? "new" : node.lambdaInformation.content_method_name);
      }
      else {
        // lambda method
        StructMethod mt = cl.getMethod(node.lambdaInformation.content_method_key);
        MethodWrapper methodWrapper = wrapper.getMethodWrapper(mt.getName(), mt.getDescriptor());
        MethodDescriptor md_content = MethodDescriptor.parseDescriptor(node.lambdaInformation.content_method_descriptor);
        MethodDescriptor md_lambda = MethodDescriptor.parseDescriptor(node.lambdaInformation.method_descriptor);

        boolean simpleLambda = false;

        if (!lambdaToAnonymous) {
          boolean lambdaParametersNeedParentheses = md_lambda.params.length != 1;

          if (lambdaParametersNeedParentheses) {
            buffer.append('(');
          }

          boolean firstParameter = true;
          int index = node.lambdaInformation.is_content_method_static ? 0 : 1;
          int start_index = md_content.params.length - md_lambda.params.length;

          for (int i = 0; i < md_content.params.length; i++) {
            if (i >= start_index) {
              if (!firstParameter) {
                buffer.append(", ");
              }

              String parameterName = methodWrapper.varproc.getVarName(new VarVersionPair(index, 0));
              buffer.append(parameterName == null ? "param" + index : parameterName); // null iff decompiled with errors

              firstParameter = false;
            }

            index += md_content.params[i].stackSize;
          }

          if (lambdaParametersNeedParentheses) {
            buffer.append(")");
          }
          buffer.append(" ->");

          RootStatement root = wrapper.getMethodWrapper(mt.getName(), mt.getDescriptor()).root;
          if (DecompilerContext.getOption(IFernflowerPreferences.INLINE_SIMPLE_LAMBDAS) && !methodWrapper.decompiledWithErrors && root != null) {
            Statement firstStat = root.getFirst();
            if (firstStat.type == Statement.TYPE_BASICBLOCK && firstStat.getExprents() != null && firstStat.getExprents().size() == 1) {
              Exprent firstExpr = firstStat.getExprents().get(0);
              boolean isVarDefinition = firstExpr.type == Exprent.EXPRENT_ASSIGNMENT &&
                ((AssignmentExprent)firstExpr).getLeft().type == Exprent.EXPRENT_VAR &&
                ((VarExprent)((AssignmentExprent)firstExpr).getLeft()).isDefinition();

              boolean isThrow = firstExpr.type == Exprent.EXPRENT_EXIT &&
                ((ExitExprent)firstExpr).getExitType() == ExitExprent.EXIT_THROW;

              if (!isVarDefinition && !isThrow) {
                simpleLambda = true;
                MethodWrapper outerWrapper = (MethodWrapper)DecompilerContext.getProperty(DecompilerContext.CURRENT_METHOD_WRAPPER);
                DecompilerContext.setProperty(DecompilerContext.CURRENT_METHOD_WRAPPER, methodWrapper);
                try {
                  TextBuffer codeBuffer = firstExpr.toJava(indent + 1, tracer);

                  if (firstExpr.type == Exprent.EXPRENT_EXIT)
                    codeBuffer.setStart(6); // skip return
                  else
                    codeBuffer.prepend(" ");

                  buffer.append(codeBuffer);
                }
                catch (Throwable ex) {
                  DecompilerContext.getLogger().writeMessage("Method " + mt.getName() + " " + mt.getDescriptor() + " in class " + node.classStruct.qualifiedName + " couldn't be written.",
                    IFernflowerLogger.Severity.WARN,
                    ex);
                  methodWrapper.decompiledWithErrors = true;
                  buffer.append(" // $FF: Couldn't be decompiled");
                }
                finally {
                  tracer.addMapping(root.getDummyExit().bytecode);
                  addTracer(cl, mt, tracer);
                  DecompilerContext.setProperty(DecompilerContext.CURRENT_METHOD_WRAPPER, outerWrapper);
                }
              }
            }
          }
        }

        if (!simpleLambda) {
          buffer.append(" {").appendLineSeparator();
          tracer.incrementCurrentSourceLine();

          methodLambdaToJava(node, wrapper, mt, buffer, indent + 1, !lambdaToAnonymous, tracer);

          buffer.appendIndent(indent).append("}");

          addTracer(cl, mt, tracer);
        }
      }
    }
    finally {
      DecompilerContext.setProperty(DecompilerContext.CURRENT_CLASS_NODE, outerNode);
    }

    DecompilerContext.getLogger().endWriteClass();
  }

  public void classToJava(ClassNode node, TextBuffer buffer, int indent, BytecodeMappingTracer tracer) {
    ClassNode outerNode = (ClassNode)DecompilerContext.getProperty(DecompilerContext.CURRENT_CLASS_NODE);
    DecompilerContext.setProperty(DecompilerContext.CURRENT_CLASS_NODE, node);

    int startLine = tracer != null ? tracer.getCurrentSourceLine() : 0;
    BytecodeMappingTracer dummy_tracer = new BytecodeMappingTracer(startLine);

    try {
      // last minute processing
      invokeProcessors(node);

      ClassWrapper wrapper = node.getWrapper();
      StructClass cl = wrapper.getClassStruct();

      DecompilerContext.getLogger().startWriteClass(cl.qualifiedName);

      // write class definition
      int start_class_def = buffer.length();
      writeClassDefinition(node, buffer, indent);

      boolean hasContent = false;
      boolean enumFields = false;

      dummy_tracer.incrementCurrentSourceLine(buffer.countLines(start_class_def));

      List<StructRecordComponent> components = cl.getRecordComponents();

      for (StructField fd : cl.getFields()) {
        boolean hide = fd.isSynthetic() && DecompilerContext.getOption(IFernflowerPreferences.REMOVE_SYNTHETIC) ||
                       wrapper.getHiddenMembers().contains(InterpreterUtil.makeUniqueKey(fd.getName(), fd.getDescriptor()));
        if (hide) continue;

        if (components != null && fd.getAccessFlags() == (CodeConstants.ACC_FINAL | CodeConstants.ACC_PRIVATE) &&
            components.stream().anyMatch(c -> c.getName().equals(fd.getName()) && c.getDescriptor().equals(fd.getDescriptor()))) {
          // Record component field: skip it
          continue;
        }

        boolean isEnum = fd.hasModifier(CodeConstants.ACC_ENUM) && DecompilerContext.getOption(IFernflowerPreferences.DECOMPILE_ENUM);
        if (isEnum) {
          if (enumFields) {
            buffer.append(',').appendLineSeparator();
            dummy_tracer.incrementCurrentSourceLine();
          }
          enumFields = true;
        }
        else if (enumFields) {
          buffer.append(';');
          buffer.appendLineSeparator();
          buffer.appendLineSeparator();
          dummy_tracer.incrementCurrentSourceLine(2);
          enumFields = false;
        }

        fieldToJava(wrapper, cl, fd, buffer, indent + 1, dummy_tracer); // FIXME: insert real tracer

        hasContent = true;
      }

      if (enumFields) {
        buffer.append(';').appendLineSeparator();
        dummy_tracer.incrementCurrentSourceLine();
      }

      // FIXME: fields don't matter at the moment
      startLine += buffer.countLines(start_class_def);

      // methods
      VBStyleCollection<StructMethod, String> methods = cl.getMethods();
      for (int i = 0; i < methods.size(); i++) {
        StructMethod mt = methods.get(i);
        boolean hide = mt.isSynthetic() && DecompilerContext.getOption(IFernflowerPreferences.REMOVE_SYNTHETIC) ||
                       mt.hasModifier(CodeConstants.ACC_BRIDGE) && DecompilerContext.getOption(IFernflowerPreferences.REMOVE_BRIDGE) ||
                       wrapper.getHiddenMembers().contains(InterpreterUtil.makeUniqueKey(mt.getName(), mt.getDescriptor()));
        if (hide) continue;

        int position = buffer.length();
        int storedLine = startLine;
        if (hasContent) {
          buffer.appendLineSeparator();
          startLine++;
        }
        BytecodeMappingTracer method_tracer = new BytecodeMappingTracer(startLine);
        boolean methodSkipped = !methodToJava(node, mt, i, buffer, indent + 1, method_tracer);
        if (!methodSkipped) {
          hasContent = true;
          addTracer(cl, mt, method_tracer);
          startLine = method_tracer.getCurrentSourceLine();
        }
        else {
          buffer.setLength(position);
          startLine = storedLine;
        }
      }

      // member classes
      for (ClassNode inner : node.nested) {
        if (inner.type == ClassNode.CLASS_MEMBER) {
          StructClass innerCl = inner.classStruct;
          boolean isSynthetic = (inner.access & CodeConstants.ACC_SYNTHETIC) != 0 || innerCl.isSynthetic();
          boolean hide = isSynthetic && DecompilerContext.getOption(IFernflowerPreferences.REMOVE_SYNTHETIC) ||
                         wrapper.getHiddenMembers().contains(innerCl.qualifiedName);
          if (hide) continue;

          if (hasContent) {
            buffer.appendLineSeparator();
            startLine++;
          }
          BytecodeMappingTracer class_tracer = new BytecodeMappingTracer(startLine);
          classToJava(inner, buffer, indent + 1, class_tracer);
          startLine = buffer.countLines();

          hasContent = true;
        }
      }

      if (!(cl.getKotlinMetadata() instanceof KotlinClassMetadata.FileFacade)) {
        buffer.appendIndent(indent).append('}');
      }

      if (node.type != ClassNode.CLASS_ANONYMOUS) {
        buffer.appendLineSeparator();
      }
    }
    finally {
      DecompilerContext.setProperty(DecompilerContext.CURRENT_CLASS_NODE, outerNode);
    }

    DecompilerContext.getLogger().endWriteClass();
  }

  @SuppressWarnings("SpellCheckingInspection")
  private static boolean isSyntheticRecordMethod(StructClass cl, StructMethod mt, TextBuffer code) {
    if (cl.getRecordComponents() != null) {
      String name = mt.getName(), descriptor = mt.getDescriptor();
      if (name.equals("equals") && descriptor.equals("(Ljava/lang/Object;)Z") ||
          name.equals("hashCode") && descriptor.equals("()I") ||
          name.equals("toString") && descriptor.equals("()Ljava/lang/String;")) {
        if (code.countLines() == 1) {
          String str = code.toString().trim();
          return str.startsWith("return this." + name + "<invokedynamic>(this");
        }
      }
    }
    return false;
  }

  // Simple heuristic to check if a method is a default getter generated by a record.
  private static boolean isDefaultRecordMethod(StructClass cl, StructMethod mt, TextBuffer code) {
    if (cl.getRecordComponents() != null) {
      String name = mt.getName();

      return code.toString().trim().equals("return this." + name + ";");
    }

    return false;
  }

  public static void packageInfoToJava(StructClass cl, TextBuffer buffer) {
    appendAnnotations(buffer, 0, cl, -1);

    int index = cl.qualifiedName.lastIndexOf('/');
    String packageName = cl.qualifiedName.substring(0, index).replace('/', '.');
    buffer.append("package ").append(packageName).append(';').appendLineSeparator().appendLineSeparator();
  }

  public static void moduleInfoToJava(StructClass cl, TextBuffer buffer) {
    appendAnnotations(buffer, 0, cl, -1);

    StructModuleAttribute moduleAttribute = cl.getAttribute(StructGeneralAttribute.ATTRIBUTE_MODULE);

    if ((moduleAttribute.moduleFlags & CodeConstants.ACC_OPEN) != 0) {
      buffer.append("open ");
    }

    buffer.append("module ").append(moduleAttribute.moduleName).append(" {").appendLineSeparator();

    writeModuleInfoBody(buffer, moduleAttribute);

    buffer.append('}').appendLineSeparator();
  }

  private static void writeModuleInfoBody(TextBuffer buffer, StructModuleAttribute moduleAttribute) {
    boolean newLineNeeded = false;

    List<StructModuleAttribute.RequiresEntry> requiresEntries = moduleAttribute.requires;
    if (!requiresEntries.isEmpty()) {
      for (StructModuleAttribute.RequiresEntry requires : requiresEntries) {
        if (!isGenerated(requires.flags)) {
          buffer.appendIndent(1).append("requires ").append(requires.moduleName.replace('/', '.')).append(';').appendLineSeparator();
          newLineNeeded = true;
        }
      }
    }

    List<StructModuleAttribute.ExportsEntry> exportsEntries = moduleAttribute.exports;
    if (!exportsEntries.isEmpty()) {
      if (newLineNeeded) buffer.appendLineSeparator();
      for (StructModuleAttribute.ExportsEntry exports : exportsEntries) {
        if (!isGenerated(exports.flags)) {
          buffer.appendIndent(1).append("exports ").append(exports.packageName.replace('/', '.'));
          List<String> exportToModules = exports.exportToModules;
          if (exportToModules.size() > 0) {
            buffer.append(" to").appendLineSeparator();
            appendFQClassNames(buffer, exportToModules);
          }
          buffer.append(';').appendLineSeparator();
          newLineNeeded = true;
        }
      }
    }

    List<StructModuleAttribute.OpensEntry> opensEntries = moduleAttribute.opens;
    if (!opensEntries.isEmpty()) {
      if (newLineNeeded) buffer.appendLineSeparator();
      for (StructModuleAttribute.OpensEntry opens : opensEntries) {
        if (!isGenerated(opens.flags)) {
          buffer.appendIndent(1).append("opens ").append(opens.packageName.replace('/', '.'));
          List<String> opensToModules = opens.opensToModules;
          if (opensToModules.size() > 0) {
            buffer.append(" to").appendLineSeparator();
            appendFQClassNames(buffer, opensToModules);
          }
          buffer.append(';').appendLineSeparator();
          newLineNeeded = true;
        }
      }
    }

    List<String> usesEntries = moduleAttribute.uses;
    if (!usesEntries.isEmpty()) {
      if (newLineNeeded) buffer.appendLineSeparator();
      for (String uses : usesEntries) {
        buffer.appendIndent(1).append("uses ").append(ExprProcessor.buildJavaClassName(uses)).append(';').appendLineSeparator();
      }
      newLineNeeded = true;
    }

    List<StructModuleAttribute.ProvidesEntry> providesEntries = moduleAttribute.provides;
    if (!providesEntries.isEmpty()) {
      if (newLineNeeded) buffer.appendLineSeparator();
      for (StructModuleAttribute.ProvidesEntry provides : providesEntries) {
        buffer.appendIndent(1).append("provides ").append(ExprProcessor.buildJavaClassName(provides.interfaceName)).append(" with").appendLineSeparator();
        appendFQClassNames(buffer, provides.implementationNames.stream().map(ExprProcessor::buildJavaClassName).collect(Collectors.toList()));
        buffer.append(';').appendLineSeparator();
      }
    }
  }

  private static boolean isGenerated(int flags) {
    return (flags & (CodeConstants.ACC_SYNTHETIC | CodeConstants.ACC_MANDATED)) != 0;
  }
  
  private static void addTracer(StructClass cls, StructMethod method, BytecodeMappingTracer tracer) {
    StructLineNumberTableAttribute table = method.getAttribute(StructGeneralAttribute.ATTRIBUTE_LINE_NUMBER_TABLE);
    tracer.setLineNumberTable(table);
    String key = InterpreterUtil.makeUniqueKey(method.getName(), method.getDescriptor());
    DecompilerContext.getBytecodeSourceMapper().addTracer(cls.qualifiedName, key, tracer);
  }

  private void writeClassDefinition(ClassNode node, TextBuffer buffer, int indent) {
    if (node.type == ClassNode.CLASS_ANONYMOUS) {
      buffer.append(" {").appendLineSeparator();
      return;
    }

    ClassWrapper wrapper = node.getWrapper();
    StructClass cl = wrapper.getClassStruct();
    @Nullable KotlinClassMetadata kotlinMetadata = cl.getKotlinMetadata();
    @Nullable KmClass kmClass = cl.getKmClass();

    int flags = node.type == ClassNode.CLASS_ROOT ? cl.getAccessFlags() : node.access;
    ModifierList modifiers = ModifierList.fromAccessFlags(flags);
    if (modifiers.has(Modifiers.FINAL)) {
      modifiers.remove(Modifiers.FINAL);
    } else if (!modifiers.has(Modifiers.PRIVATE) && !modifiers.has(Modifiers.ABSTRACT)) {
      modifiers.add(Modifiers.OPEN);
    }
    boolean isDeprecated = cl.hasAttribute(StructGeneralAttribute.ATTRIBUTE_DEPRECATED);
    boolean isSynthetic = (flags & CodeConstants.ACC_SYNTHETIC) != 0 || cl.hasAttribute(StructGeneralAttribute.ATTRIBUTE_SYNTHETIC) || kotlinMetadata instanceof KotlinClassMetadata.SyntheticClass;
    boolean isEnum = DecompilerContext.getOption(IFernflowerPreferences.DECOMPILE_ENUM) && ((flags & CodeConstants.ACC_ENUM) != 0 || (kmClass != null && Flag.Class.IS_ENUM_CLASS.invoke(kmClass.getFlags())));
    boolean isInterface = (flags & CodeConstants.ACC_INTERFACE) != 0
      || (kmClass != null && Flag.Class.IS_INTERFACE.invoke(kmClass.getFlags()))
      || (kmClass != null && Flag.Class.IS_ANNOTATION_CLASS.invoke(kmClass.getFlags()));
    boolean isAnnotation = (flags & CodeConstants.ACC_ANNOTATION) != 0 || (kmClass != null && Flag.Class.IS_ANNOTATION_CLASS.invoke(kmClass.getFlags()));
    boolean isModuleInfo = (flags & CodeConstants.ACC_MODULE) != 0 && cl.hasAttribute(StructGeneralAttribute.ATTRIBUTE_MODULE);
    // TODO KOTLIN: multi file class facade

    if (isDeprecated) {
      appendDeprecation(buffer, indent);
    }

    if (interceptor != null) {
      String oldName = interceptor.getOldName(cl.qualifiedName);
      appendRenameComment(buffer, oldName, MType.CLASS, indent);
    }

    if (isSynthetic) {
      appendComment(buffer, "synthetic class", indent);
    }

    if (javadocProvider != null) {
      appendJavadoc(buffer, javadocProvider.getClassDoc(cl), indent);
    }

    appendAnnotations(buffer, indent, cl, -1);

    if (kotlinMetadata instanceof KotlinClassMetadata.FileFacade) {
      return;
    }

    buffer.appendIndent(indent);

    if (isEnum) {
      // remove abstract and final flags (JLS 8.9 Enums)
      modifiers.remove(Modifiers.ABSTRACT);
      modifiers.remove(Modifiers.OPEN);
    }

    List<StructRecordComponent> components = cl.getRecordComponents();

    if (node.type != ClassNode.CLASS_ROOT && (flags & CodeConstants.ACC_STATIC) != 0) {
      modifiers.add(Modifiers.INNER); // TODO KOTLIN: @JvmStatic
    }

    if (modifiers.toJava(buffer)) {
      buffer.append(' ');
    }

    if (isEnum) {
      buffer.append("enum ");
    }
    else if (isInterface) {
      if (isAnnotation) {
        buffer.append("annotation class ");
      } else {
        buffer.append("interface ");
      }
    }
    else if (isModuleInfo) {
      StructModuleAttribute moduleAttribute = cl.getAttribute(StructGeneralAttribute.ATTRIBUTE_MODULE);

      if ((moduleAttribute.moduleFlags & CodeConstants.ACC_OPEN) != 0) {
        buffer.append("open ");
      }

      buffer.append("module ");
      buffer.append(moduleAttribute.moduleName);
    }
    else if (components != null) {
      buffer.append("data class "); // TODO KOTLIN @JvmRecord
    }
    else {
      buffer.append("class "); // TODO KOTLIN be smarter with class metadata
    }
    buffer.append(node.simpleName);

    GenericClassDescriptor descriptor = cl.getSignature();
    if (descriptor != null && !descriptor.fparameters.isEmpty()) {
      appendTypeParameters(buffer, descriptor.fparameters, descriptor.fbounds);
    }

    if (components != null) {
      // TODO KOTLIN: constructor
      buffer.append('(');
      for (int i = 0; i < components.size(); i++) {
        StructRecordComponent cd = components.get(i);
        if (i > 0) {
          buffer.append(", ");
        }
        boolean varArgComponent = i == components.size() - 1 && isVarArgRecord(cl);
        recordComponentToJava(cd, buffer, varArgComponent);
      }
      buffer.append(')');
    }

    buffer.append(' ');

    boolean hasExtends = false;
    if (!isEnum && !isInterface && components == null && cl.superClass != null) {
      VarType supertype = new VarType(cl.superClass.getString(), true);
      if (!VarType.VARTYPE_OBJECT.equals(supertype)) {
        hasExtends = true;
        buffer.append(" : ");
        buffer.append(ExprProcessor.getCastTypeName(descriptor == null ? supertype : descriptor.superclass));
        buffer.append("()"); // TODO KOTLIN: superconstructor call
        buffer.append(' ');
      }
    }

    if (!isAnnotation) {
      int[] interfaces = cl.getInterfaces();
      if (interfaces.length > 0) {
        if (!hasExtends) {
          buffer.append(" : ");
        }
        for (int i = 0; i < interfaces.length; i++) {
          if (i > 0 || hasExtends) {
            buffer.append(", ");
          }
          buffer.append(ExprProcessor.getCastTypeName(descriptor == null ? new VarType(cl.getInterface(i), true) : descriptor.superinterfaces.get(i)));
        }
        buffer.append(' ');
      }
    }

    buffer.append('{').appendLineSeparator();
  }

  private static boolean isVarArgRecord(StructClass cl) {
    String canonicalConstructorDescriptor =
      cl.getRecordComponents().stream().map(c -> c.getDescriptor()).collect(Collectors.joining("", "(", ")V"));
    StructMethod init = cl.getMethod(CodeConstants.INIT_NAME, canonicalConstructorDescriptor);
    return init != null && init.hasModifier(CodeConstants.ACC_VARARGS);
  }

  private void fieldToJava(ClassWrapper wrapper, StructClass cl, StructField fd, TextBuffer buffer, int indent, BytecodeMappingTracer tracer) {
    int start = buffer.length();
    @Nullable KmProperty kmProperty = fd.getKmProperty(cl);
    boolean isInterface = cl.hasModifier(CodeConstants.ACC_INTERFACE);
    boolean isDeprecated = fd.hasAttribute(StructGeneralAttribute.ATTRIBUTE_DEPRECATED);
    boolean isEnum = fd.hasModifier(CodeConstants.ACC_ENUM) && DecompilerContext.getOption(IFernflowerPreferences.DECOMPILE_ENUM);
    ModifierList modifierList = ModifierList.fromAccessFlags(fd.getAccessFlags());

    if (isDeprecated) {
      appendDeprecation(buffer, indent);
    }

    if (interceptor != null) {
      String oldName = interceptor.getOldName(cl.qualifiedName + " " + fd.getName() + " " + fd.getDescriptor());
      appendRenameComment(buffer, oldName, MType.FIELD, indent);
    }

    if (fd.isSynthetic()) {
      appendComment(buffer, "synthetic field", indent);
    }

    if (javadocProvider != null) {
      appendJavadoc(buffer, javadocProvider.getFieldDoc(cl, fd), indent);
    }
    appendAnnotations(buffer, indent, fd, TypeAnnotation.FIELD);

    buffer.appendIndent(indent);

    boolean isVar = kmProperty != null ? Flag.Property.IS_VAR.invoke(kmProperty.getFlags()) : !modifierList.has(Modifiers.FINAL);
    modifierList.remove(Modifiers.FINAL);

    if (kmProperty != null && Flag.Property.IS_CONST.invoke(kmProperty.getFlags())) {
      modifierList.add(Modifiers.CONST);
    }

    if (!isEnum) {
      if (modifierList.toJava(buffer)) {
        buffer.append(' ');
      }
    }

    buffer.append(isVar ? "var " : "val ");

    buffer.append(fd.getName());

    Map.Entry<VarType, GenericFieldDescriptor> fieldTypeData = getFieldTypeData(fd);
    VarType fieldType = fieldTypeData.getKey();
    GenericFieldDescriptor descriptor = fieldTypeData.getValue();

    if (!isEnum) {
      buffer.append(": ").append(ExprProcessor.getCastTypeName(descriptor == null ? fieldType : descriptor.type));
    }

    tracer.incrementCurrentSourceLine(buffer.countLines(start));

    Exprent initializer;
    if (fd.hasModifier(CodeConstants.ACC_STATIC)) {
      initializer = wrapper.getStaticFieldInitializers().getWithKey(InterpreterUtil.makeUniqueKey(fd.getName(), fd.getDescriptor()));
    }
    else {
      initializer = wrapper.getDynamicFieldInitializers().getWithKey(InterpreterUtil.makeUniqueKey(fd.getName(), fd.getDescriptor()));
    }
    if (initializer != null) {
      if (isEnum && initializer.type == Exprent.EXPRENT_NEW) {
        NewExprent expr = (NewExprent)initializer;
        expr.setEnumConst(true);
        buffer.append(expr.toJava(indent, tracer));
      }
      else {
        buffer.append(" = ");

        if (initializer.type == Exprent.EXPRENT_CONST) {
          ((ConstExprent) initializer).adjustConstType(fieldType);
        }

        // FIXME: special case field initializer. Can map to more than one method (constructor) and bytecode instruction.
        ExprProcessor.getCastedExprent(initializer, descriptor == null ? fieldType : descriptor.type, buffer, indent, false, tracer);
      }
    }
    else if (fd.hasModifier(CodeConstants.ACC_FINAL) && fd.hasModifier(CodeConstants.ACC_STATIC)) {
      StructConstantValueAttribute attr = fd.getAttribute(StructGeneralAttribute.ATTRIBUTE_CONSTANT_VALUE);
      if (attr != null) {
        PrimitiveConstant constant = cl.getPool().getPrimitiveConstant(attr.getIndex());
        buffer.append(" = ");
        buffer.append(new ConstExprent(fieldType, constant.value, null).toJava(indent, tracer));
      }
    }
    else {
      buffer.append(" = null"); // TODO KOTLIN: figure out better behaviour
    }

    if (!isEnum) {
      buffer.appendLineSeparator();
      tracer.incrementCurrentSourceLine();
    }
  }

  private static void recordComponentToJava(StructRecordComponent cd, TextBuffer buffer, boolean varArgComponent) {
    appendAnnotations(buffer, -1, cd, TypeAnnotation.FIELD);

    Map.Entry<VarType, GenericFieldDescriptor> fieldTypeData = getFieldTypeData(cd);
    VarType fieldType = fieldTypeData.getKey();
    GenericFieldDescriptor descriptor = fieldTypeData.getValue();

    if (descriptor != null) {
      buffer.append(ExprProcessor.getCastTypeName(varArgComponent ? descriptor.type.decreaseArrayDim() : descriptor.type));
    }
    else {
      buffer.append(ExprProcessor.getCastTypeName(varArgComponent ? fieldType.decreaseArrayDim() : fieldType));
    }
    if (varArgComponent) {
      buffer.append("...");
    }
    buffer.append(' ');

    buffer.append(cd.getName());
  }

  private static void methodLambdaToJava(ClassNode lambdaNode,
                                         ClassWrapper classWrapper,
                                         StructMethod mt,
                                         TextBuffer buffer,
                                         int indent,
                                         boolean codeOnly, BytecodeMappingTracer tracer) {
    MethodWrapper methodWrapper = classWrapper.getMethodWrapper(mt.getName(), mt.getDescriptor());

    MethodWrapper outerWrapper = (MethodWrapper)DecompilerContext.getProperty(DecompilerContext.CURRENT_METHOD_WRAPPER);
    DecompilerContext.setProperty(DecompilerContext.CURRENT_METHOD_WRAPPER, methodWrapper);

    try {
      String method_name = lambdaNode.lambdaInformation.method_name;
      MethodDescriptor md_content = MethodDescriptor.parseDescriptor(lambdaNode.lambdaInformation.content_method_descriptor);
      MethodDescriptor md_lambda = MethodDescriptor.parseDescriptor(lambdaNode.lambdaInformation.method_descriptor);

      if (!codeOnly) {
        buffer.appendIndent(indent);
        buffer.append("public ");
        buffer.append(method_name);
        buffer.append("(");

        boolean firstParameter = true;
        int index = lambdaNode.lambdaInformation.is_content_method_static ? 0 : 1;
        int start_index = md_content.params.length - md_lambda.params.length;

        for (int i = 0; i < md_content.params.length; i++) {
          if (i >= start_index) {
            if (!firstParameter) {
              buffer.append(", ");
            }

            String typeName = ExprProcessor.getCastTypeName(md_content.params[i].copy());
            if (ExprProcessor.UNDEFINED_TYPE_STRING.equals(typeName) &&
                DecompilerContext.getOption(IFernflowerPreferences.UNDEFINED_PARAM_TYPE_OBJECT)) {
              typeName = ExprProcessor.getCastTypeName(VarType.VARTYPE_OBJECT);
            }

            buffer.append(typeName);
            buffer.append(" ");

            String parameterName = methodWrapper.varproc.getVarName(new VarVersionPair(index, 0));
            buffer.append(parameterName == null ? "param" + index : parameterName); // null iff decompiled with errors

            firstParameter = false;
          }

          index += md_content.params[i].stackSize;
        }

        buffer.append(") {").appendLineSeparator();

        indent += 1;
      }

      RootStatement root = classWrapper.getMethodWrapper(mt.getName(), mt.getDescriptor()).root;
      if (!methodWrapper.decompiledWithErrors) {
        if (root != null) { // check for existence
          try {
            buffer.append(root.toJava(indent, tracer));
          }
          catch (Throwable t) {
            String message = "Method " + mt.getName() + " " + mt.getDescriptor() + " in class " + lambdaNode.classStruct.qualifiedName + " couldn't be written.";
            DecompilerContext.getLogger().writeMessage(message, IFernflowerLogger.Severity.WARN, t);
            methodWrapper.decompiledWithErrors = true;
          }
        }
      }

      if (methodWrapper.decompiledWithErrors) {
        buffer.appendIndent(indent);
        buffer.append("// $FF: Couldn't be decompiled");
        buffer.appendLineSeparator();
      }

      if (root != null) {
        tracer.addMapping(root.getDummyExit().bytecode);
      }

      if (!codeOnly) {
        indent -= 1;
        buffer.appendIndent(indent).append('}').appendLineSeparator();
      }
    }
    finally {
      DecompilerContext.setProperty(DecompilerContext.CURRENT_METHOD_WRAPPER, outerWrapper);
    }
  }

  private static String toValidJavaIdentifier(String name) {
    if (name == null || name.isEmpty()) return name;

    boolean changed = false;
    StringBuilder res = new StringBuilder(name.length());
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if ((i == 0 && !Character.isJavaIdentifierStart(c))
          || (i > 0 && !Character.isJavaIdentifierPart(c))) {
        changed = true;
        res.append("_");
      }
      else {
        res.append(c);
      }
    }
    if (!changed) {
      return name;
    }
    return res.append("/* $FF was: ").append(name).append("*/").toString();
  }

  private boolean methodToJava(ClassNode node, StructMethod mt, int methodIndex, TextBuffer buffer, int indent, BytecodeMappingTracer tracer) {
    ClassWrapper wrapper = node.getWrapper();
    StructClass cl = wrapper.getClassStruct();
    @Nullable KmClass kmClass = cl.getKmClass();
    @Nullable KmFunction kmFunction = mt.getKmFunction(cl);
    // Get method by index, this keeps duplicate methods (with the same key) separate
    MethodWrapper methodWrapper = wrapper.getMethodWrapper(methodIndex);

    boolean hideMethod = false;
    int start_index_method = buffer.length();

    MethodWrapper outerWrapper = (MethodWrapper)DecompilerContext.getProperty(DecompilerContext.CURRENT_METHOD_WRAPPER);
    DecompilerContext.setProperty(DecompilerContext.CURRENT_METHOD_WRAPPER, methodWrapper);

    try {
      int flags = mt.getAccessFlags();
      if ((flags & CodeConstants.ACC_NATIVE) != 0) {
        flags &= ~CodeConstants.ACC_STRICT; // compiler bug: a strictfp class sets all methods to strictfp
      }
      if (CodeConstants.CLINIT_NAME.equals(mt.getName())) {
        flags &= CodeConstants.ACC_STATIC; // ignore all modifiers except 'static' in a static initializer
      }
      ModifierList modifiers = ModifierList.fromAccessFlags(flags);
      if (modifiers.has(Modifiers.FINAL)) {
        modifiers.remove(Modifiers.FINAL);
      } else if (!modifiers.has(Modifiers.PRIVATE) && !modifiers.has(Modifiers.ABSTRACT) && !cl.hasModifier(CodeConstants.ACC_PRIVATE)) {
        modifiers.add(Modifiers.OPEN);
      }

      boolean isInterface = cl.hasModifier(CodeConstants.ACC_INTERFACE)
        || (kmClass != null && Flag.Class.IS_INTERFACE.invoke(kmClass.getFlags()))
        || (kmClass != null && Flag.Class.IS_ANNOTATION_CLASS.invoke(kmClass.getFlags()));
      boolean isAnnotation = cl.hasModifier(CodeConstants.ACC_ANNOTATION) || (kmClass != null && Flag.Class.IS_ANNOTATION_CLASS.invoke(kmClass.getFlags()));
      boolean isEnum = (cl.hasModifier(CodeConstants.ACC_ENUM) || (kmClass != null && Flag.Class.IS_ENUM_CLASS.invoke(kmClass.getFlags())))
        && DecompilerContext.getOption(IFernflowerPreferences.DECOMPILE_ENUM);
      boolean isDeprecated = mt.hasAttribute(StructGeneralAttribute.ATTRIBUTE_DEPRECATED);
      boolean clInit = false, init = false, dInit = false;

      MethodDescriptor md = MethodDescriptor.parseDescriptor(mt, node);

      if (isDeprecated) {
        appendDeprecation(buffer, indent);
      }

      if (interceptor != null) {
        String oldName = interceptor.getOldName(cl.qualifiedName + " " + mt.getName() + " " + mt.getDescriptor());
        appendRenameComment(buffer, oldName, MType.METHOD, indent);
      }

      boolean isSynthetic = (flags & CodeConstants.ACC_SYNTHETIC) != 0 || mt.hasAttribute(StructGeneralAttribute.ATTRIBUTE_SYNTHETIC);
      boolean isBridge = (flags & CodeConstants.ACC_BRIDGE) != 0;
      if (isSynthetic) {
        appendComment(buffer, "synthetic method", indent);
      }
      if (isBridge) {
        appendComment(buffer, "bridge method", indent);
      }

      if (javadocProvider != null) {
        appendJavadoc(buffer, javadocProvider.getMethodDoc(cl, mt), indent);
      }

      appendAnnotations(buffer, indent, mt, TypeAnnotation.METHOD_RETURN_TYPE);

      buffer.appendIndent(indent);

      // Try append @Override after all other annotations
      if (!CodeConstants.INIT_NAME.equals(mt.getName()) && !CodeConstants.CLINIT_NAME.equals(mt.getName()) && !mt.hasModifier(CodeConstants.ACC_STATIC)  && !mt.hasModifier(CodeConstants.ACC_PRIVATE)) {
        // Search superclasses for methods that match the name and descriptor of this one.
        // Make sure not to search the current class otherwise it will return the current method itself!
        // TODO: record overrides
        boolean isOverride = searchForMethod(cl, mt.getName(), md, false);
        if (isOverride) {
          buffer.append("override ");
        }
      }

      if (modifiers.toJava(buffer)) {
        buffer.append(' ');
      }

//      if (isInterface && !mt.hasModifier(CodeConstants.ACC_STATIC) && mt.containsCode()) {
//        // 'default' modifier (Java 8)
//        buffer.append("default ");
//      }

      String name = mt.getName();
      if (CodeConstants.INIT_NAME.equals(name)) {
        if (node.type == ClassNode.CLASS_ANONYMOUS) {
          name = "init";
          dInit = true;
        }
        else {
          name = "constructor";
          init = true;
        }
      }
      else if (CodeConstants.CLINIT_NAME.equals(name)) {
        name = "";
        clInit = true;
      }

      GenericMethodDescriptor descriptor = mt.getSignature();
      boolean throwsExceptions = false;
      int paramCount = 0;

      if (!clInit && !dInit) {
        KmType receiverType = kmFunction == null ? null : kmFunction.getReceiverParameterType();
        boolean thisVar = !mt.hasModifier(CodeConstants.ACC_STATIC) || receiverType != null;

        if (descriptor != null && !descriptor.typeParameters.isEmpty()) {
          appendTypeParameters(buffer, descriptor.typeParameters, descriptor.typeParameterBounds);
          buffer.append(' ');
        }

        if (!init) {
          buffer.append("fun ");
        }

        if (receiverType != null) {
          if (receiverType.classifier instanceof KmClassifier.Class) { // TODO KOTLIN: KmType to string
            buffer.append(DecompilerContext.getImportCollector().getShortName(((KmClassifier.Class) receiverType.classifier).getName()));
            buffer.append('.');
          }
        }

        buffer.append(toValidJavaIdentifier(name));
        buffer.append('(');

        List<VarVersionPair> mask = methodWrapper.synthParameters;

        int lastVisibleParameterIndex = -1;
        for (int i = 0; i < md.params.length; i++) {
          if (mask == null || mask.get(i) == null) {
            lastVisibleParameterIndex = i;
          }
        }

        List<StructMethodParametersAttribute.Entry> methodParameters = null;
        if (DecompilerContext.getOption(IFernflowerPreferences.USE_METHOD_PARAMETERS)) {
          StructMethodParametersAttribute attr = mt.getAttribute(StructGeneralAttribute.ATTRIBUTE_METHOD_PARAMETERS);
          if (attr != null) {
            methodParameters = attr.getEntries();
          }
        }

        int index = isEnum && init ? 3 : thisVar ? 1 : 0;
        int start = isEnum && init ? 2 : 0;
        boolean hasDescriptor = descriptor != null;
        //mask should now have the Outer.this in it... so this *shouldn't* be nessasary.
        //if (init && !isEnum && ((node.access & CodeConstants.ACC_STATIC) == 0) && node.type == ClassNode.CLASS_MEMBER)
        //    index++;

        for (int i = start; i < md.params.length; i++) {
          VarType parameterType = hasDescriptor ? descriptor.parameterTypes.get(paramCount) : md.params[i];
          if (mask == null || mask.get(i) == null) {
            if (paramCount > 0) {
              buffer.append(", ");
            }

            appendParameterAnnotations(buffer, mt, paramCount);

            if (methodParameters != null && i < methodParameters.size()) {
              //appendModifiers(buffer, methodParameters.get(i).myAccessFlags, CodeConstants.ACC_FINAL, isInterface, 0);
            }
//            else if (methodWrapper.varproc.getVarFinal(new VarVersionPair(index, 0)) == VarTypeProcessor.VAR_EXPLICIT_FINAL) {
//              buffer.append("final ");
//            }

            String typeName;
            boolean isVarArg = i == lastVisibleParameterIndex && mt.hasModifier(CodeConstants.ACC_VARARGS) && parameterType.arrayDim > 0;
            if (isVarArg) {
                parameterType = parameterType.decreaseArrayDim();
            }
            typeName = ExprProcessor.getCastTypeName(parameterType);

            if (ExprProcessor.UNDEFINED_TYPE_STRING.equals(typeName) &&
                DecompilerContext.getOption(IFernflowerPreferences.UNDEFINED_PARAM_TYPE_OBJECT)) {
              typeName = ExprProcessor.getCastTypeName(VarType.VARTYPE_OBJECT);
            }
            if (isVarArg) {
              buffer.append("vararg ");
            }

            String parameterName;
            if (methodParameters != null && i < methodParameters.size()) {
              parameterName = methodParameters.get(i).myName;
            }
            else {
              parameterName = methodWrapper.varproc.getVarName(new VarVersionPair(index, 0));
            }

            if ((flags & (CodeConstants.ACC_ABSTRACT | CodeConstants.ACC_NATIVE)) != 0) {
              String newParameterName = methodWrapper.methodStruct.getVariableNamer().renameAbstractParameter(parameterName, index);
              parameterName = !newParameterName.equals(parameterName) ? newParameterName : DecompilerContext.getStructContext().renameAbstractParameter(methodWrapper.methodStruct.getClassQualifiedName(), mt.getName(), mt.getDescriptor(), index - (((flags & CodeConstants.ACC_STATIC) == 0) ? 1 : 0), parameterName);

            }

            buffer.append(parameterName == null ? "param" + index : parameterName); // null iff decompiled with errors

            buffer.append(": ").append(typeName);

            paramCount++;
          }

          index += parameterType.stackSize;
        }

        buffer.append(')');

        if (!init) {
          VarType ret = descriptor == null ? md.ret : descriptor.returnType;
          if (!ret.equals(VarType.VARTYPE_VOID)) { // TODO KOTLIN: Unit
            buffer.append(": ").append(ExprProcessor.getCastTypeName(ret));
          }
        }

        // TODO KOTLIN: @Throws
//        StructExceptionsAttribute attr = mt.getAttribute(StructGeneralAttribute.ATTRIBUTE_EXCEPTIONS);
//        if ((descriptor != null && !descriptor.exceptionTypes.isEmpty()) || attr != null) {
//          throwsExceptions = true;
//          buffer.append(" throws ");
//
//          boolean useDescriptor = hasDescriptor && !descriptor.exceptionTypes.isEmpty();
//          for (int i = 0; i < attr.getThrowsExceptions().size(); i++) {
//            if (i > 0) {
//              buffer.append(", ");
//            }
//            VarType type = useDescriptor ? descriptor.exceptionTypes.get(i) : new VarType(attr.getExcClassname(i, cl.getPool()), true);
//            buffer.append(ExprProcessor.getCastTypeName(type));
//          }
//        }
      }

      tracer.incrementCurrentSourceLine(buffer.countLines(start_index_method));

      if ((flags & (CodeConstants.ACC_ABSTRACT | CodeConstants.ACC_NATIVE)) != 0) { // native or abstract method (explicit or interface)
        if (isAnnotation) {
          StructAnnDefaultAttribute attr = mt.getAttribute(StructGeneralAttribute.ATTRIBUTE_ANNOTATION_DEFAULT);
          if (attr != null) {
            buffer.append(" default ");
            buffer.append(attr.getDefaultValue().toJava(0, BytecodeMappingTracer.DUMMY));
          }
        }

        buffer.appendLineSeparator();
      }
      else {
        if (!clInit && !dInit) {
          buffer.append(' ');
        }

        // We do not have line information for method start, lets have it here for now
        buffer.append('{').appendLineSeparator();
        tracer.incrementCurrentSourceLine();

        RootStatement root = methodWrapper.root;

        if (root != null && !methodWrapper.decompiledWithErrors) { // check for existence
          try {
            // to restore in case of an exception
            BytecodeMappingTracer codeTracer = new BytecodeMappingTracer(tracer.getCurrentSourceLine());
            TextBuffer code = root.toJava(indent + 1, codeTracer);

            hideMethod = code.length() == 0 && (clInit || dInit || hideConstructor(node, init, throwsExceptions, paramCount, flags)) ||
                         isSyntheticRecordMethod(cl, mt, code) || isDefaultRecordMethod(cl, mt, code);
            
            buffer.append(code);

            tracer.setCurrentSourceLine(codeTracer.getCurrentSourceLine());
            tracer.addTracer(codeTracer);
          }
          catch (Throwable t) {
            String message = "Method " + mt.getName() + " " + mt.getDescriptor() + " in class " + node.classStruct.qualifiedName + " couldn't be written.";
            DecompilerContext.getLogger().writeMessage(message, IFernflowerLogger.Severity.WARN, t);
            methodWrapper.decompiledWithErrors = true;
          }
        }

        if (methodWrapper.decompiledWithErrors) {
          buffer.appendIndent(indent + 1);
          buffer.append("// $FF: Couldn't be decompiled");
          buffer.appendLineSeparator();
          tracer.incrementCurrentSourceLine();
        }
        else if (root != null) {
          tracer.addMapping(root.getDummyExit().bytecode);
        }
        buffer.appendIndent(indent).append('}').appendLineSeparator();
      }

      tracer.incrementCurrentSourceLine();
    }
    finally {
      DecompilerContext.setProperty(DecompilerContext.CURRENT_METHOD_WRAPPER, outerWrapper);
    }

    // save total lines
    // TODO: optimize
    //tracer.setCurrentSourceLine(buffer.countLines(start_index_method));

    return !hideMethod;
  }

  private static boolean hideConstructor(ClassNode node, boolean init, boolean throwsExceptions, int paramCount, int methodAccessFlags) {
    if (!init || throwsExceptions || paramCount > 0 || !DecompilerContext.getOption(IFernflowerPreferences.HIDE_DEFAULT_CONSTRUCTOR)) {
      return false;
    }

    ClassWrapper wrapper = node.getWrapper();
	  StructClass cl = wrapper.getClassStruct();

	  int classAccessFlags = node.type == ClassNode.CLASS_ROOT ? cl.getAccessFlags() : node.access;
    boolean isEnum = cl.hasModifier(CodeConstants.ACC_ENUM) && DecompilerContext.getOption(IFernflowerPreferences.DECOMPILE_ENUM);

    // default constructor requires same accessibility flags. Exception: enum constructor which is always private
  	if(!isEnum && ((classAccessFlags & ACCESSIBILITY_FLAGS) != (methodAccessFlags & ACCESSIBILITY_FLAGS))) {
  	  return false;
  	}

    int count = 0;
    for (StructMethod mt : cl.getMethods()) {
      if (CodeConstants.INIT_NAME.equals(mt.getName())) {
        if (++count > 1) {
          return false;
        }
      }
    }

    return true;
  }

  private static Map.Entry<VarType, GenericFieldDescriptor> getFieldTypeData(StructField fd) {
    VarType fieldType = new VarType(fd.getDescriptor(), false);

    GenericFieldDescriptor descriptor = fd.getSignature();
    return new AbstractMap.SimpleImmutableEntry<>(fieldType, descriptor);
  }

  private static void appendDeprecation(TextBuffer buffer, int indent) {
    buffer.appendIndent(indent).append("/** @deprecated */").appendLineSeparator();
  }

  private enum MType {CLASS, FIELD, METHOD}

  private static void appendRenameComment(TextBuffer buffer, String oldName, MType type, int indent) {
    if (oldName == null) return;

    buffer.appendIndent(indent);
    buffer.append("// $FF: renamed from: ");

    switch (type) {
      case CLASS:
        buffer.append(ExprProcessor.buildJavaClassName(oldName));
        break;

      case FIELD:
        String[] fParts = oldName.split(" ");
        FieldDescriptor fd = FieldDescriptor.parseDescriptor(fParts[2]);
        buffer.append(fParts[1]);
        buffer.append(' ');
        buffer.append(getTypePrintOut(fd.type));
        break;

      default:
        String[] mParts = oldName.split(" ");
        MethodDescriptor md = MethodDescriptor.parseDescriptor(mParts[2]);
        buffer.append(mParts[1]);
        buffer.append(" (");
        boolean first = true;
        for (VarType paramType : md.params) {
          if (!first) {
            buffer.append(", ");
          }
          first = false;
          buffer.append(getTypePrintOut(paramType));
        }
        buffer.append(") ");
        buffer.append(getTypePrintOut(md.ret));
    }

    buffer.appendLineSeparator();
  }

  private static String getTypePrintOut(VarType type) {
    String typeText = ExprProcessor.getCastTypeName(type, false);
    if (ExprProcessor.UNDEFINED_TYPE_STRING.equals(typeText) &&
        DecompilerContext.getOption(IFernflowerPreferences.UNDEFINED_PARAM_TYPE_OBJECT)) {
      typeText = ExprProcessor.getCastTypeName(VarType.VARTYPE_OBJECT, false);
    }
    return typeText;
  }

  private static void appendComment(TextBuffer buffer, String comment, int indent) {
    buffer.appendIndent(indent).append("// $FF: ").append(comment).appendLineSeparator();
  }
  
  private static void appendJavadoc(TextBuffer buffer, String javaDoc, int indent) {
    if (javaDoc == null) return;
    buffer.appendIndent(indent).append("/**").appendLineSeparator();
    for (String s : javaDoc.split("\n")) {
      buffer.appendIndent(indent).append(" * ").append(s).appendLineSeparator();
    }
    buffer.appendIndent(indent).append(" */").appendLineSeparator();
  }

  private static final StructGeneralAttribute.Key<?>[] ANNOTATION_ATTRIBUTES = {
    StructGeneralAttribute.ATTRIBUTE_RUNTIME_VISIBLE_ANNOTATIONS, StructGeneralAttribute.ATTRIBUTE_RUNTIME_INVISIBLE_ANNOTATIONS};
  private static final StructGeneralAttribute.Key<?>[] PARAMETER_ANNOTATION_ATTRIBUTES = {
    StructGeneralAttribute.ATTRIBUTE_RUNTIME_VISIBLE_PARAMETER_ANNOTATIONS, StructGeneralAttribute.ATTRIBUTE_RUNTIME_INVISIBLE_PARAMETER_ANNOTATIONS};
  private static final StructGeneralAttribute.Key<?>[] TYPE_ANNOTATION_ATTRIBUTES = {
    StructGeneralAttribute.ATTRIBUTE_RUNTIME_VISIBLE_TYPE_ANNOTATIONS, StructGeneralAttribute.ATTRIBUTE_RUNTIME_INVISIBLE_TYPE_ANNOTATIONS};

  private static void appendAnnotations(TextBuffer buffer, int indent, StructMember mb, int targetType) {
    Set<String> filter = new HashSet<>();

    for (StructGeneralAttribute.Key<?> key : ANNOTATION_ATTRIBUTES) {
      StructAnnotationAttribute attribute = (StructAnnotationAttribute)mb.getAttribute(key);
      if (attribute != null) {
        for (AnnotationExprent annotation : attribute.getAnnotations()) {
          String text = annotation.toJava(indent, BytecodeMappingTracer.DUMMY).toString();
          filter.add(text);
          buffer.append(text);
          if (indent < 0) {
            buffer.append(' ');
          }
          else {
            buffer.appendLineSeparator();
          }
        }
      }
    }

    appendTypeAnnotations(buffer, indent, mb, targetType, -1, filter);
  }

  // Returns true if a method with the given name and descriptor matches in the inheritance tree of the superclass.
  private static boolean searchForMethod(StructClass cl, String name, MethodDescriptor md, boolean search) {
    // Didn't find the class or the library containing the class wasn't loaded, can't search
    if (cl == null) {
      return false;
    }

    VBStyleCollection<StructMethod, String> methods = cl.getMethods();

    if (search) {
      // If we're allowed to search, iterate through the methods and try to find matches
      for (StructMethod method : methods) {
        // Match against name, descriptor, and whether or not the found method is static.
        // TODO: We are not handling generics or superclass parameters and return types
        if (md.equals(MethodDescriptor.parseDescriptor(method.getDescriptor())) && name.equals(method.getName()) && !method.hasModifier(CodeConstants.ACC_STATIC)) {
          return true;
        }
      }
    }

    // If we have a superclass that's not Object, search that as well
    if (cl.superClass != null) {
      StructClass superClass = DecompilerContext.getStructContext().getClass((String)cl.superClass.value);

      boolean foundInSuperClass = searchForMethod(superClass, name, md, true);

      if (foundInSuperClass) {
        return true;
      }
    }

    // Search all of the interfaces implemented by this class for the method
    for (String ifaceName : cl.getInterfaceNames()) {
      StructClass iface = DecompilerContext.getStructContext().getClass(ifaceName);

      boolean foundInIface = searchForMethod(iface, name, md, true);

      if (foundInIface) {
        return true;
      }
    }

    // We didn't manage to find anything, return
    return false;
  }

  private static void appendParameterAnnotations(TextBuffer buffer, StructMethod mt, int param) {
    Set<String> filter = new HashSet<>();

    for (StructGeneralAttribute.Key<?> key : PARAMETER_ANNOTATION_ATTRIBUTES) {
      StructAnnotationParameterAttribute attribute = (StructAnnotationParameterAttribute)mt.getAttribute(key);
      if (attribute != null) {
        List<List<AnnotationExprent>> annotations = attribute.getParamAnnotations();
        if (param < annotations.size()) {
          for (AnnotationExprent annotation : annotations.get(param)) {
            String text = annotation.toJava(-1, BytecodeMappingTracer.DUMMY).toString();
            filter.add(text);
            buffer.append(text).append(' ');
          }
        }
      }
    }

    appendTypeAnnotations(buffer, -1, mt, TypeAnnotation.METHOD_PARAMETER, param, filter);
  }

  private static void appendTypeAnnotations(TextBuffer buffer, int indent, StructMember mb, int targetType, int index, Set<String> filter) {
    for (StructGeneralAttribute.Key<?> key : TYPE_ANNOTATION_ATTRIBUTES) {
      StructTypeAnnotationAttribute attribute = (StructTypeAnnotationAttribute)mb.getAttribute(key);
      if (attribute != null) {
        for (TypeAnnotation annotation : attribute.getAnnotations()) {
          if (annotation.isTopLevel() && annotation.getTargetType() == targetType && (index < 0 || annotation.getIndex() == index)) {
            String text = annotation.getAnnotation().toJava(indent, BytecodeMappingTracer.DUMMY).toString();
            if (!filter.contains(text)) {
              buffer.append(text);
              if (indent < 0) {
                buffer.append(' ');
              }
              else {
                buffer.appendLineSeparator();
              }
            }
          }
        }
      }
    }
  }

  private static final int ACCESSIBILITY_FLAGS = CodeConstants.ACC_PUBLIC | CodeConstants.ACC_PROTECTED | CodeConstants.ACC_PRIVATE;

  public static void appendTypeParameters(TextBuffer buffer, List<String> parameters, List<List<VarType>> bounds) {
    buffer.append('<');

    for (int i = 0; i < parameters.size(); i++) {
      if (i > 0) {
        buffer.append(", ");
      }

      buffer.append(parameters.get(i));

      List<VarType> parameterBounds = bounds.get(i);
      if (parameterBounds.size() > 1 || !"java/lang/Object".equals(parameterBounds.get(0).value)) {
        buffer.append(" extends ");
        buffer.append(ExprProcessor.getCastTypeName(parameterBounds.get(0)));
        for (int j = 1; j < parameterBounds.size(); j++) {
          buffer.append(" & ");
          buffer.append(ExprProcessor.getCastTypeName(parameterBounds.get(j)));
        }
      }
    }

    buffer.append('>');
  }

  private static void appendFQClassNames(TextBuffer buffer, List<String> names) {
    for (int i = 0; i < names.size(); i++) {
      String name = names.get(i);
      buffer.appendIndent(2).append(name);
      if (i < names.size() - 1) {
        buffer.append(',').appendLineSeparator();
      }
    }
  }
}
