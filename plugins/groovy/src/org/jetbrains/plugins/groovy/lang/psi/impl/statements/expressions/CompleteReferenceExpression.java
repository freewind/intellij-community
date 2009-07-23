package org.jetbrains.plugins.groovy.lang.psi.impl.statements.expressions;

import com.intellij.codeInsight.PsiEquivalenceUtil;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PropertyUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.plugins.grails.lang.gsp.resolve.taglib.GspTagLibUtil;
import org.jetbrains.plugins.groovy.GroovyIcons;
import org.jetbrains.plugins.groovy.lang.completion.GroovyCompletionUtil;
import org.jetbrains.plugins.groovy.lang.lexer.GroovyTokenTypes;
import org.jetbrains.plugins.groovy.lang.psi.GrReferenceElement;
import org.jetbrains.plugins.groovy.lang.psi.GroovyFileBase;
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElement;
import org.jetbrains.plugins.groovy.lang.psi.api.GroovyResolveResult;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrField;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.arguments.GrArgumentList;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrCall;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrConstructorCall;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.path.GrCallExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrTypeDefinition;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrAccessorMethod;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrMember;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrMethod;
import org.jetbrains.plugins.groovy.lang.psi.impl.GroovyPsiManager;
import org.jetbrains.plugins.groovy.lang.psi.impl.PsiImplUtil;
import org.jetbrains.plugins.groovy.lang.psi.util.PsiUtil;
import org.jetbrains.plugins.groovy.lang.resolve.ResolveUtil;
import org.jetbrains.plugins.groovy.lang.resolve.processors.CompletionProcessor;
import org.jetbrains.plugins.groovy.lang.resolve.processors.ResolverProcessor;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * @author ven
 */
public class CompleteReferenceExpression {
  public static Object[] getVariants(GrReferenceExpressionImpl refExpr) {
    PsiElement refParent = refExpr.getParent();
    List<String> namedArgsVariants = new LinkedList<String>();
    if (refParent instanceof GrArgumentList) {
      PsiElement refPParent = refParent.getParent();
      if (refPParent instanceof GrCall) {
        GroovyResolveResult[] results = new GroovyResolveResult[0];
        //costructor call
        if (refPParent instanceof GrConstructorCall) {
          GrConstructorCall constructorCall = (GrConstructorCall)refPParent;
          results = ArrayUtil.mergeArrays(results, constructorCall.multiResolveConstructor(), GroovyResolveResult.class);
        } else

        //call expression (method or new expression)
        if (refPParent instanceof GrCallExpression) {
          GrCallExpression constructorCall = (GrCallExpression)refPParent;
          results = ArrayUtil.mergeArrays(results, constructorCall.getMethodVariants(), GroovyResolveResult.class);
        } else

        if (refPParent instanceof GrApplicationStatementImpl) {
          final GrExpression element = ((GrApplicationStatementImpl)refPParent).getFunExpression();
          if (element instanceof GrReferenceElement) {
            results = ArrayUtil.mergeArrays(results, ((GrReferenceElement) element).multiResolve(true), GroovyResolveResult.class);
          }
        }


        for (GroovyResolveResult result : results) {
          PsiElement element = result.getElement();
          if (element instanceof GrMethod) {
            Set<String>[] parametersArray = ((GrMethod)element).getNamedParametersArray();
            for (Set<String> namedParameters : parametersArray) {
              namedArgsVariants.addAll(namedParameters);
            }
          }
        }
      }
    }

    final GrExpression qualifier = refExpr.getQualifierExpression();
    if (isGspNamespaceQualifier(qualifier)) {
      return new Object[0];
    }
    Object[] propertyVariants = getVariantsImpl(refExpr, CompletionProcessor.createPropertyCompletionProcessor(refExpr));
    PsiType type = null;
    if (qualifier == null) {
      PsiElement parent = refParent;
      if (parent instanceof GrArgumentList) {
        final PsiElement pparent = parent.getParent();
        if (pparent instanceof GrExpression) {
          GrExpression call = (GrExpression)pparent; //add named argument label variants
          type = call.getType();
        }
      }
    }
    else {
      type = qualifier.getType();
    }

    if (type instanceof PsiClassType) {
      PsiClass clazz = ((PsiClassType)type).resolve();
      if (clazz != null) {
        List<LookupElement> props = getPropertyVariants(refExpr, clazz);

        if (props.size() > 0) {
          propertyVariants = ArrayUtil.mergeArrays(propertyVariants, props.toArray(new Object[props.size()]), Object.class);
        }

        propertyVariants = ArrayUtil.mergeArrays(propertyVariants, clazz.getFields(), Object.class);

        List<Object> variantList = new ArrayList<Object>();
        for (Object variant : propertyVariants) {
          if (variant instanceof GrField && ((GrField)variant).isProperty()) continue;
          variantList.add(variant);
        }

        propertyVariants = variantList.toArray(new Object[variantList.size()]);
      }
    }

    propertyVariants =
      ArrayUtil.mergeArrays(propertyVariants, namedArgsVariants.toArray(new Object[namedArgsVariants.size()]), Object.class);

    if (refExpr.getKind() == GrReferenceExpressionImpl.Kind.TYPE_OR_PROPERTY) {
      ResolverProcessor classVariantsCollector = CompletionProcessor.createClassCompletionProcessor(refExpr);
      final Object[] classVariants = getVariantsImpl(refExpr, classVariantsCollector);
      return ArrayUtil.mergeArrays(propertyVariants, classVariants, Object.class);
    }
    else {
      return propertyVariants;
    }
  }

  private static List<LookupElement> getPropertyVariants(GrReferenceExpression refExpr, PsiClass clazz) {
    List<LookupElement> props = new ArrayList<LookupElement>();
    final LookupElementFactory factory = LookupElementFactory.getInstance();
    final PsiClass eventListener =
      JavaPsiFacade.getInstance(refExpr.getProject()).findClass("java.util.EventListener", refExpr.getResolveScope());
    for (PsiMethod method : clazz.getAllMethods()) {
      if (PsiUtil.isSimplePropertySetter(method)) {
        String prop = PropertyUtil.getPropertyName(method);
        if (prop != null) {
          props.add(factory.createLookupElement(prop).setIcon(GroovyIcons.PROPERTY));
        }
      }
      else if (eventListener != null) {
        addListenerProperties(method, eventListener, props, factory);
      }
    }
    return props;
  }

  private static void addListenerProperties(PsiMethod method,
                                            PsiClass eventListenerClass,
                                            List<LookupElement> result,
                                            LookupElementFactory factory) {
    if (method.getName().startsWith("add") && method.getParameterList().getParametersCount() == 1) {
      final PsiParameter parameter = method.getParameterList().getParameters()[0];
      final PsiType type = parameter.getType();
      if (type instanceof PsiClassType) {
        final PsiClassType classType = (PsiClassType)type;
        final PsiClass listenerClass = classType.resolve();
        if (listenerClass != null) {
          final PsiMethod[] listenerMethods = listenerClass.getMethods();
          if (InheritanceUtil.isInheritorOrSelf(listenerClass, eventListenerClass, true)) {
            for (PsiMethod listenerMethod : listenerMethods) {
              result.add(factory.createLookupElement(listenerMethod.getName()).setIcon(GroovyIcons.PROPERTY));
            }
          }
        }
      }
    }
  }


  private static Object[] getVariantsImpl(GrReferenceExpression refExpr, ResolverProcessor processor) {
    GrExpression qualifier = refExpr.getQualifierExpression();
    String[] sameQualifier = getVariantsWithSameQualifier(qualifier, refExpr);
    if (qualifier == null) {
      ResolveUtil.treeWalkUp(refExpr, processor);
      PsiClass contextClass = PsiUtil.getContextClass(refExpr);
      if (contextClass != null) {
        PsiClassType classType = JavaPsiFacade.getInstance(refExpr.getProject()).getElementFactory().createType(contextClass);
        ResolveUtil.processNonCodeMethods(classType, processor, refExpr.getProject(), refExpr, true);
      }
      qualifier = PsiImplUtil.getRuntimeQualifier(refExpr);
      if (qualifier != null) {
        getVariantsFromQualifier(refExpr, processor, qualifier);
      }
    }
    else {
      if (refExpr.getDotTokenType() != GroovyTokenTypes.mSPREAD_DOT) {
        getVariantsFromQualifier(refExpr, processor, qualifier);
      }
      else {
        getVariantsFromQualifierForSpreadOperator(refExpr, processor, qualifier);
      }
    }

    GroovyResolveResult[] candidates = processor.getCandidates();
    if (candidates.length == 0 && sameQualifier.length == 0) return PsiNamedElement.EMPTY_ARRAY;
    candidates = filterStaticsOK(candidates);
    PsiElement[] elements = ResolveUtil.mapToElements(candidates);
    if (qualifier == null) {
      List<GroovyResolveResult> nonPackages = ContainerUtil.findAll(candidates, new Condition<GroovyResolveResult>() {
        public boolean value(final GroovyResolveResult result) {
          return !(result.getElement() instanceof PsiPackage);
        }
      });
      candidates = nonPackages.toArray(new GroovyResolveResult[nonPackages.size()]);
    }
    LookupElement[] propertyLookupElements = addPretendedProperties(elements);
    Object[] variants = GroovyCompletionUtil.getCompletionVariants(candidates);
    variants = ArrayUtil.mergeArrays(variants, propertyLookupElements, Object.class);
    return ArrayUtil.mergeArrays(variants, sameQualifier, Object.class);
  }

  private static boolean isGspNamespaceQualifier(final GrExpression qualifier) {
    if (qualifier == null) return false;
    PsiReference reference = qualifier.getReference();
    if (reference == null) return false;
    PsiElement element = reference.resolve();
    if (!(element instanceof GrAccessorMethod)) return false;
    return GspTagLibUtil.isNamespaceField(((GrAccessorMethod)element));
  }

  private static GroovyResolveResult[] filterStaticsOK(GroovyResolveResult[] candidates) {
    List<GroovyResolveResult> result = new ArrayList<GroovyResolveResult>(candidates.length);
    for (GroovyResolveResult resolveResult : candidates) {
      if (resolveResult.isStaticsOK()) result.add(resolveResult);
    }
    return result.toArray(new GroovyResolveResult[result.size()]);
  }

  private static void getVariantsFromQualifierForSpreadOperator(GrReferenceExpression refExpr,
                                                                ResolverProcessor processor,
                                                                GrExpression qualifier) {
    PsiType qualifierType = qualifier.getType();
    if (qualifierType instanceof PsiClassType) {
      PsiClassType.ClassResolveResult result = ((PsiClassType)qualifierType).resolveGenerics();
      PsiClass clazz = result.getElement();
      if (clazz != null) {
        PsiClass listClass = JavaPsiFacade.getInstance(refExpr.getProject()).findClass("java.util.List", refExpr.getResolveScope());
        if (listClass != null && listClass.getTypeParameters().length == 1) {
          PsiSubstitutor substitutor = TypeConversionUtil.getClassSubstitutor(listClass, clazz, result.getSubstitutor());
          if (substitutor != null) {
            PsiType componentType = substitutor.substitute(listClass.getTypeParameters()[0]);
            if (componentType != null) {
              getVariantsFromQualifierType(refExpr, processor, componentType, refExpr.getProject());
            }
          }
        }
      }
    }
    else if (qualifierType instanceof PsiArrayType) {
      getVariantsFromQualifierType(refExpr, processor, ((PsiArrayType)qualifierType).getComponentType(), refExpr.getProject());
    }
  }

  private static LookupElement[] addPretendedProperties(PsiElement[] elements) {
    List<LookupElement> result = new ArrayList<LookupElement>();
    final LookupElementFactory factory = LookupElementFactory.getInstance();

    for (PsiElement element : elements) {
      if (element instanceof PsiMethod && !(element instanceof GrAccessorMethod)) {
        PsiMethod method = (PsiMethod)element;
        if (PsiUtil.isSimplePropertyAccessor(method)) {
          String propName = PropertyUtil.getPropertyName(method);
          if (propName != null) {
            if (!PsiUtil.isValidReferenceName(propName)) {
              propName = "'" + propName + "'";
            }
            result.add(factory.createLookupElement(propName).setIcon(GroovyIcons.PROPERTY));
          }
        }
      }
    }

    return result.toArray(new LookupElement[result.size()]);
  }

  private static void getVariantsFromQualifier(GrReferenceExpression refExpr, ResolverProcessor processor, GrExpression qualifier) {
    Project project = qualifier.getProject();
    PsiType qualifierType = qualifier.getType();
    if (qualifierType == null) {
      if (qualifier instanceof GrReferenceExpression) {
        PsiElement resolved = ((GrReferenceExpression)qualifier).resolve();
        if (resolved instanceof PsiPackage) {
          resolved.processDeclarations(processor, ResolveState.initial(), null, refExpr);
          return;
        }
        if (resolved instanceof GrAccessorMethod) {
          PsiType propertyType = ((GrAccessorMethod)resolved).getProperty().getTypeGroovy();
          if (propertyType != null) {
            getVariantsFromQualifierType(refExpr, processor, propertyType, project);
            return;
          }
        }
      }
      final PsiClassType type = JavaPsiFacade.getInstance(refExpr.getProject()).getElementFactory()
        .createTypeByFQClassName(GrTypeDefinition.DEFAULT_BASE_CLASS_NAME, refExpr.getResolveScope());
      getVariantsFromQualifierType(refExpr, processor, type, project);
    }
    else {
      if (qualifierType instanceof PsiIntersectionType) {
        for (PsiType conjunct : ((PsiIntersectionType)qualifierType).getConjuncts()) {
          getVariantsFromQualifierType(refExpr, processor, conjunct, project);
        }
      }
      else {
        getVariantsFromQualifierType(refExpr, processor, qualifierType, project);
        if (qualifier instanceof GrReferenceExpression) {
          PsiElement resolved = ((GrReferenceExpression)qualifier).resolve();
          if (resolved instanceof PsiClass) { ////omitted .class
            GlobalSearchScope scope = refExpr.getResolveScope();
            PsiClass javaLangClass = PsiUtil.getJavaLangClass(resolved, scope);
            if (javaLangClass != null) {
              PsiSubstitutor substitutor = PsiSubstitutor.EMPTY;
              PsiTypeParameter[] typeParameters = javaLangClass.getTypeParameters();
              if (typeParameters.length == 1) {
                substitutor = substitutor.put(typeParameters[0], qualifierType);
              }
              javaLangClass.processDeclarations(processor, ResolveState.initial(), null, refExpr);
              PsiType javaLangClassType =
                JavaPsiFacade.getInstance(refExpr.getProject()).getElementFactory().createType(javaLangClass, substitutor);
              ResolveUtil.processNonCodeMethods(javaLangClassType, processor, refExpr.getProject(), refExpr, true);
            }
          }
        }
      }
    }
  }

  private static String[] getVariantsWithSameQualifier(GrExpression qualifier, GrReferenceExpression refExpr) {
    if (qualifier != null && qualifier.getType() != null) return ArrayUtil.EMPTY_STRING_ARRAY;

    final PsiElement scope = PsiTreeUtil.getParentOfType(refExpr, GrMember.class, GroovyFileBase.class);
    List<String> result = new ArrayList<String>();
    addVariantsWithSameQualifier(scope, refExpr, qualifier, result);
    return result.toArray(new String[result.size()]);
  }

  private static void addVariantsWithSameQualifier(PsiElement element,
                                                   GrReferenceExpression patternExpression,
                                                   GrExpression patternQualifier,
                                                   List<String> result) {
    if (element instanceof GrReferenceExpression && element != patternExpression && !PsiUtil.isLValue((GroovyPsiElement)element)) {
      final GrReferenceExpression refExpr = (GrReferenceExpression)element;
      final String refName = refExpr.getReferenceName();
      if (refName != null && refExpr.resolve() == null) {
        final GrExpression hisQualifier = refExpr.getQualifierExpression();
        if (hisQualifier != null && patternQualifier != null) {
          if (PsiEquivalenceUtil.areElementsEquivalent(hisQualifier, patternQualifier)) {
            result.add(refName);
          }
        }
        else if (hisQualifier == null && patternQualifier == null) {
          result.add(refName);
        }
      }
    }

    for (PsiElement child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
      addVariantsWithSameQualifier(child, patternExpression, patternQualifier, result);
    }
  }

  private static void getVariantsFromQualifierType(GrReferenceExpression refExpr,
                                                   ResolverProcessor processor,
                                                   PsiType qualifierType,
                                                   Project project) {
    if (qualifierType instanceof PsiClassType) {
      PsiClass qualifierClass = ((PsiClassType)qualifierType).resolve();
      if (qualifierClass != null) {
        qualifierClass.processDeclarations(processor, ResolveState.initial(), null, refExpr);
      }
      if (!ResolveUtil.processCategoryMembers(refExpr, processor, (PsiClassType)qualifierType)) return;
    }
    else if (qualifierType instanceof PsiArrayType) {
      final GrTypeDefinition arrayClass = GroovyPsiManager.getInstance(project).getArrayClass();
      if (!arrayClass.processDeclarations(processor, ResolveState.initial(), null, refExpr)) return;
    }
    else if (qualifierType instanceof PsiIntersectionType) {
      for (PsiType conjunct : ((PsiIntersectionType)qualifierType).getConjuncts()) {
        getVariantsFromQualifierType(refExpr, processor, conjunct, project);
      }
      return;
    }

    ResolveUtil.processNonCodeMethods(qualifierType, processor, project, refExpr, true);
  }
}
