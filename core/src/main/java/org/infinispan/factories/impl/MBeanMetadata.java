package org.infinispan.factories.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.infinispan.commons.stat.GaugeMetricInfo;
import org.infinispan.commons.stat.MetricInfo;
import org.infinispan.commons.stat.TimerMetricInfo;
import org.infinispan.commons.stat.TimerTracker;
import org.infinispan.jmx.annotations.MBean;
import org.infinispan.jmx.annotations.ManagedAttribute;
import org.infinispan.jmx.annotations.ManagedOperation;

/**
 * JMX related component metadata, as expressed by {@link MBean}, {@link ManagedAttribute} and {@link ManagedOperation}
 * annotations.
 *
 * @author Dan Berindei
 * @since 10.0
 */
public final class MBeanMetadata {
   private final String jmxObjectName;
   private final String description;
   private final String superMBeanClassName;
   private final Collection<AttributeMetadata> attributes;
   private final Collection<OperationMetadata> operations;
   private final Scopes scope;

   public static MBeanMetadata of(String objectName, String description, String superMBeanClassName, Scopes scope,
                                  Object... attributesAndOperations) {
      List<AttributeMetadata> attributes = new ArrayList<>();
      List<OperationMetadata> operations = new ArrayList<>();
      for (Object attributeOrOperation : attributesAndOperations) {
         if (attributeOrOperation instanceof AttributeMetadata) {
            attributes.add((AttributeMetadata) attributeOrOperation);
         } else if (attributeOrOperation instanceof OperationMetadata) {
            operations.add((OperationMetadata) attributeOrOperation);
         } else {
            throw new IllegalArgumentException();
         }
      }
      return new MBeanMetadata(objectName, description, superMBeanClassName, scope, attributes, operations);
   }

   public MBeanMetadata(String jmxObjectName, String description, String superMBeanClassName, Scopes scope,
                        Collection<AttributeMetadata> attributes, Collection<OperationMetadata> operations) {
      this.jmxObjectName = jmxObjectName == null  || jmxObjectName.trim().isEmpty() ? null : jmxObjectName;
      this.description = description;
      this.superMBeanClassName = superMBeanClassName;
      this.scope = scope;
      this.attributes = attributes;
      this.operations = operations;
   }

   public String getJmxObjectName() {
      return jmxObjectName;
   }

   public String getDescription() {
      return description;
   }

   public String getSuperMBeanClassName() {
      return superMBeanClassName;
   }

   public Scopes scope() {
      return scope;
   }

   public Collection<AttributeMetadata> getAttributes() {
      return attributes;
   }

   public Collection<OperationMetadata> getOperations() {
      return operations;
   }

   @Override
   public String toString() {
      return "MBeanMetadata{" +
            "jmxObjectName='" + jmxObjectName + '\'' +
            ", description='" + description + '\'' +
            ", super=" + superMBeanClassName +
            ", attributes=" + attributes +
            ", operations=" + operations +
            '}';
   }

   public static final class AttributeMetadata {

      private final String name;
      private final String description;
      private final boolean writable;
      private final boolean useSetter;
      private final String type;
      private final boolean is;
      private final Function<?, ?> getterFunction;  // optional
      private final BiConsumer<?, ?> setterFunction; // optional
      private final boolean clusterWide;

      public AttributeMetadata(String name, String description, boolean writable, boolean useSetter, String type,
                               boolean is, Function<?, ?> getterFunction, BiConsumer<?, ?> setterFunction, boolean clusterWide) {
         this.name = name;
         this.description = description;
         this.writable = writable;
         this.useSetter = useSetter;
         this.type = type;
         this.is = is;
         this.getterFunction = getterFunction;
         this.setterFunction = setterFunction;
         this.clusterWide = clusterWide;
      }

      public AttributeMetadata(String name, String description, boolean writable, boolean useSetter, String type,
                               boolean is, Function<?, ?> getterFunction, BiConsumer<?, ?> setterFunction) {
         this(name, description, writable, useSetter, type, is, getterFunction, setterFunction, false);
      }

      public String getName() {
         return name;
      }

      public String getDescription() {
         return description;
      }

      public boolean isWritable() {
         return writable;
      }

      public boolean isUseSetter() {
         return useSetter;
      }

      public String getType() {
         return type;
      }

      public boolean isIs() {
         return is;
      }

      public boolean isClusterWide() {
         return clusterWide;
      }

      @SuppressWarnings("unchecked")
      public Optional<MetricInfo> toMetricInfo() {
         if (isClusterWide()) {
            return Optional.empty();
         }
         if (getterFunction != null) {
            return Optional.of(new GaugeMetricInfo<>(name, description, null, (Function<Object, Number>) getterFunction));
         } else if (setterFunction != null) {
            return Optional.of(new TimerMetricInfo<>(name, description, null, (BiConsumer<Object, TimerTracker>) setterFunction));
         }
         return Optional.empty();
      }

      @Override
      public String toString() {
         return "AttributeMetadata{" +
               "name='" + name + '\'' +
               ", description='" + description + '\'' +
               ", writable=" + writable +
               ", type='" + type + '\'' +
               ", is=" + is +
               ", clusterWide=" + clusterWide +
               ", getterFunction=" + getterFunction +
               ", setterFunction=" + setterFunction +
               '}';
      }
   }

   public static final class OperationMetadata {

      private final String methodName;
      private final String operationName;
      private final String description;
      private final String returnType;
      private final OperationParameterMetadata[] methodParameters;

      public OperationMetadata(String methodName, String operationName, String description, String returnType,
                               OperationParameterMetadata... methodParameters) {
         this.methodName = methodName;
         this.operationName = operationName.isEmpty() ? methodName : operationName;
         this.description = description;
         this.returnType = returnType;
         this.methodParameters = methodParameters;
      }

      public String getDescription() {
         return description;
      }

      public String getOperationName() {
         return operationName;
      }

      public String getMethodName() {
         return methodName;
      }

      public OperationParameterMetadata[] getMethodParameters() {
         return methodParameters;
      }

      public String getReturnType() {
         return returnType;
      }

      public String getSignature() {
         StringBuilder signature = new StringBuilder();
         signature.append(methodName).append('(');
         if (methodParameters != null) {
            boolean first = true;
            for (OperationParameterMetadata param : methodParameters) {
               if (first) {
                  first = false;
               } else {
                  signature.append(',');
               }
               signature.append(param.getType());
            }
         }
         signature.append(')');
         return signature.toString();
      }

      @Override
      public String toString() {
         return "OperationMetadata{" +
               "methodName='" + methodName + '\'' +
               ", returnType=" + returnType +
               ", methodParameters=" + (methodParameters == null ? null : Arrays.toString(methodParameters)) +
               ", description='" + description + '\'' +
               '}';
      }
   }

   public static final class OperationParameterMetadata {

      private final String name;
      private final String type;
      private final String description;

      public OperationParameterMetadata(String name, String type, String description) {
         this.name = name;
         this.type = type;
         this.description = description;
      }

      public String getName() {
         return name;
      }

      public String getType() {
         return type;
      }

      public String getDescription() {
         return description;
      }

      @Override
      public String toString() {
         return "OperationParameter{name='" + name + "', type=" + type + ", description='" + description + "'}";
      }
   }
}
