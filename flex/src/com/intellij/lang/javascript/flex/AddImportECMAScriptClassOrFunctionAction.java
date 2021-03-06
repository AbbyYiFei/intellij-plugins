package com.intellij.lang.javascript.flex;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.hint.QuestionAction;
import com.intellij.codeInsight.navigation.NavigationUtil;
import com.intellij.codeInspection.HintAction;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.injected.editor.VirtualFileWindow;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.lang.javascript.JSBundle;
import com.intellij.lang.javascript.psi.JSFunction;
import com.intellij.lang.javascript.psi.JSNewExpression;
import com.intellij.lang.javascript.psi.JSReferenceExpression;
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeList;
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeListOwner;
import com.intellij.lang.javascript.psi.ecmal4.JSClass;
import com.intellij.lang.javascript.psi.ecmal4.JSQualifiedNamedElement;
import com.intellij.lang.javascript.psi.impl.JSPsiImplUtils;
import com.intellij.lang.javascript.psi.resolve.JSResolveUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.GlobalSearchScopes;
import com.intellij.psi.search.PsiElementProcessor;
import com.intellij.util.SmartList;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;

/**
 * @author Maxim.Mossienko
 *         Date: Apr 25, 2008
 *         Time: 8:36:38 PM
 */
public class AddImportECMAScriptClassOrFunctionAction implements HintAction, QuestionAction, LocalQuickFix {
  private final PsiPolyVariantReference myReference;
  private Editor myEditor;
  private boolean isAvailable;
  private boolean isAvailableCalculated;
  private long modificationCount = -1;
  private String myText = "";
  private final boolean myUnambiguousTheFlyMode;

  public AddImportECMAScriptClassOrFunctionAction(final Editor editor, final PsiPolyVariantReference psiReference) {
    this(editor, psiReference, false);
  }

  public AddImportECMAScriptClassOrFunctionAction(final Editor editor,
                                                  final PsiPolyVariantReference psiReference,
                                                  final boolean unambiguousTheFlyMode) {
    myReference = psiReference;
    myEditor = editor;
    myUnambiguousTheFlyMode = unambiguousTheFlyMode;
  }

  public boolean showHint(@NotNull final Editor editor) {
    myEditor = editor;
    final PsiElement element = myReference.getElement();
    TextRange range = InjectedLanguageManager.getInstance(element.getProject()).injectedToHost(element, element.getTextRange());
    HintManager.getInstance().showQuestionHint(editor, getText(), range.getStartOffset(), range.getEndOffset(), this);
    return true;
  }

  @NotNull
  public String getText() {
    return myText;
  }

  @NotNull
  public String getFamilyName() {
    return getText();
  }

  public void applyFix(@NotNull final Project project, @NotNull final ProblemDescriptor descriptor) {
    invoke(project, myEditor, descriptor.getPsiElement().getContainingFile());
  }

  public boolean isAvailable(@NotNull final Project project, final Editor editor, final PsiFile file) {
    if (!myReference.getElement().isValid()) return false;
    final long modL = myReference.getElement().getManager().getModificationTracker().getModificationCount();

    if (!isAvailableCalculated || modL != modificationCount) {
      final ResolveResult[] results = myReference.multiResolve(false);
      boolean hasValidResult = false;

      for(ResolveResult r:results) {
        if (r.isValidResult()) {
          hasValidResult = true;
          break;
        }
      }

      if (!hasValidResult) {
        final Collection<JSQualifiedNamedElement> candidates = getCandidates(editor, file);

        isAvailableCalculated = true;
        isAvailable = candidates.size() > 0;
        String text;

        if (isAvailable) {
          final JSQualifiedNamedElement element = candidates.iterator().next();
          text = element.getQualifiedName() + "?";
          if (candidates.size() > 1) text += " (multiple choices...)";
          if (!ApplicationManager.getApplication().isHeadlessEnvironment()) {
            text += " Alt+Enter";
          }
        } else {
          text = "";
        }
        myText = text;
      } else {
        isAvailableCalculated = true;
        isAvailable = false;
        myText = "";
      }

      modificationCount = modL;
    }

    return isAvailable;
  }

  private Collection<JSQualifiedNamedElement> getCandidates(Editor editor, PsiFile file) {
    final Collection<JSQualifiedNamedElement> candidates;

    if (myReference instanceof JSReferenceExpression && ((JSReferenceExpression)myReference).getQualifier() == null) {
      Collection<JSQualifiedNamedElement> c = getCandidates(editor, file, myReference.getCanonicalText());
      filterCandidates(c);
      candidates = new THashSet<>(c, JSPsiImplUtils.QUALIFIED_NAME_HASHING_STRATEGY);
    }
    else {
      JSQualifiedNamedElement invalidResult = null;

      for (ResolveResult r : myReference.multiResolve(false)) {
        PsiElement element = r.getElement();
        if (element instanceof JSQualifiedNamedElement) {
          invalidResult = (JSQualifiedNamedElement)element;
        }
      }
      if (invalidResult != null) {
        if(myReference.getElement().getParent() instanceof JSNewExpression && invalidResult instanceof JSFunction &&
           ((JSFunction)invalidResult).isConstructor()) {
          invalidResult = (JSClass)invalidResult.getParent();
        }
        candidates = new SmartList<>();
        candidates.add(invalidResult);
      }
      else {
        candidates = Collections.emptyList();
      }
    }
    return candidates;
  }

  public static Collection<JSQualifiedNamedElement> getCandidates(final Editor editor, final PsiFile file, final String name) {
    final Module module = ModuleUtil.findModuleForPsiElement(file);
    if (module != null) {
      GlobalSearchScope searchScope;
      VirtualFile virtualFile = file.getVirtualFile();
      if (virtualFile instanceof VirtualFileWindow) virtualFile = ((VirtualFileWindow)virtualFile).getDelegate();

      if (GlobalSearchScopes.projectProductionScope(file.getProject()).contains(virtualFile)) { // skip tests suggestions
        searchScope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, false);
      } else {
        searchScope = JSResolveUtil.getResolveScope(file);
      }
      return JSResolveUtil.findElementsByName(name, editor.getProject(), searchScope);
    }
    else {
      return Collections.emptyList();
    }
  }

  public void invoke(@NotNull final Project project, final Editor editor, final PsiFile file) {
    final Collection<JSQualifiedNamedElement> candidates = getCandidates(editor, file);

    if (candidates.isEmpty() || myUnambiguousTheFlyMode && candidates.size() != 1) {
      return;
    }

    if (candidates.size() > 1) {
      NavigationUtil.getPsiElementPopup(
          candidates.toArray(new JSQualifiedNamedElement[candidates.size()]),
          new JSQualifiedNamedElementRenderer(),
        JSBundle.message("choose.class.to.import.title"),
        new PsiElementProcessor<JSQualifiedNamedElement>() {
          public boolean execute(@NotNull final JSQualifiedNamedElement element) {
            CommandProcessor.getInstance().executeCommand(
                project,
                () -> doImport(element.getQualifiedName()),
                "Import " + element.getQualifiedName(),
                this
             );

            return false;
          }
        }
      ).showInBestPositionFor(editor);
    }
    else {
      final JSQualifiedNamedElement element = candidates.iterator().next();
      if (myUnambiguousTheFlyMode) {
        CommandProcessor.getInstance().runUndoTransparentAction(() -> doImport(element.getQualifiedName()));
      }
      else {
        CommandProcessor.getInstance().executeCommand(
          project,
          () -> doImport(element.getQualifiedName()),
          "Import " + element.getQualifiedName(),
          this
        );
      }
    }
  }

  private void doImport(final String qName) {
    ApplicationManager.getApplication().runWriteAction(() -> {
      final PsiElement element = myReference.getElement();
      SmartPsiElementPointer<PsiElement> pointer =
        SmartPointerManager.getInstance(element.getProject()).createSmartPsiElementPointer(element);
      ImportUtils.doImport(element, qName, true);
      PsiElement newElement = pointer.getElement();
      if (newElement != null) {
        ImportUtils.insertUseNamespaceIfNeeded(qName, newElement);
      }
    });
  }

  public boolean startInWriteAction() {
    return false;
  }

  public boolean execute() {
    final PsiFile containingFile = myReference.getElement().getContainingFile();
    invoke(containingFile.getProject(), myEditor, containingFile);

    return true;
  }

  private static void filterCandidates(Collection<JSQualifiedNamedElement>candidates) {
    for (Iterator<JSQualifiedNamedElement> i = candidates.iterator(); i.hasNext();) {
      JSQualifiedNamedElement element = i.next();
      if (!element.getQualifiedName().contains(".")) {
        i.remove();
      }
      else if (element instanceof JSAttributeListOwner &&
               ((JSAttributeListOwner)element).getAttributeList().getAccessType() != JSAttributeList.AccessType.PUBLIC) {
        i.remove();
      }
    }
  }
}
