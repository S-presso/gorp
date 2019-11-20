/* 
 * Copyright (c) 2016, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license. 
 * For full license text, see LICENSE.txt file in the repo root  or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.gorp.model;

import com.salesforce.gorp.DefinitionParseException;
import com.salesforce.gorp.ExtractionResult;
import com.salesforce.gorp.io.InputLine;

import java.util.*;

public class CookedDefinitions
{
    protected Map<String,LiteralPattern> _patterns = new LinkedHashMap<>();

    protected Map<String,CookedTemplate> _templates = new LinkedHashMap<>();

    protected List<FlattenedExtraction> _extractions = null;

    protected List<Integer> _reserved_patterns = new ArrayList<Integer>();

    public CookedDefinitions() { }

    public LiteralPattern findPattern(String name) {
        return _patterns.get(name);
    }

    public CookedTemplate findTemplate(String name) {
        return _templates.get(name);
    }

    public Map<String,LiteralPattern> getPatterns() {
        return _patterns;
    }

    public Map<String,CookedTemplate> getTemplates() {
        return _templates;
    }

    public List<FlattenedExtraction> getExtractions() {
        if (_extractions == null) {
            throw new IllegalStateException("Extractions not yet flattened");
        }
        return _extractions;
    }

    public List<Integer> getReservedPatterns() {
        return _reserved_patterns;
    }

    /*
    /**********************************************************************
    /* Resolution: patterns
    /**********************************************************************
     */

    /**
     * First part of resolution: resolving and flattening of pattern declarations.
     * After this step, 
     */
    public void resolvePatterns(UncookedDefinitions uncooked) throws DefinitionParseException
    {
        Map<String,UncookedDefinition> uncookedPatterns = uncooked.getPatterns(); 
        for (UncookedDefinition pattern : uncookedPatterns.values()) {
            String name = pattern.getName();
            if (_patterns.containsKey(name)) { // due to recursion, may have done it already
                continue;
            }
            /*if (/*pattern == null && ExtractionResult.isReservedPattern(name)) {
                InputLine marker = InputLine.create(null, 0, String.format("[%s]", name));
                pattern = new UncookedDefinition(marker, name, false, 0);
            }*/

            _patterns.put(name, _resolvePattern(uncookedPatterns, name, pattern, null));
        }
    }

    private LiteralPattern _resolvePattern(Map<String,UncookedDefinition> uncookedPatterns,
            String name, UncookedDefinition def,
            List<String> stack) throws DefinitionParseException
    {
        // Minor optimization: we might have just one part
        List<DefPiece> pieces = def.getParts();
        if (pieces.size() <= 1) {

            DefPiece piece;
            if (pieces.size() == 1) {
                piece = pieces.get(0);
                if (piece instanceof LiteralPattern) {
                    return (LiteralPattern) piece;
                }
            }
            else
                piece = new PatternReference(def.getSource(), 0, name);

            // must be reference (only literals and refs)
            if (stack == null) {
                stack = new LinkedList<>();
            }
            return _resolvePatternReference(uncookedPatterns, name, (PatternReference) piece, stack);
        }
        StringBuilder sb = new StringBuilder(100);
        for (DefPiece piece : pieces) {
            LiteralPattern lit;
            if (piece instanceof LiteralPattern) {
                lit = (LiteralPattern) piece;
            } else {
                // must be reference (only literals and refs)
                if (stack == null) {
                    stack = new LinkedList<>();
                }
                lit = _resolvePatternReference(uncookedPatterns, name, (PatternReference) piece, stack);
            }
            sb.append(lit.getText());
        }
        return new LiteralPattern(def.getSource(), pieces.get(0).getSourceOffset(), sb.toString());
    }

    private LiteralPattern _resolvePatternReference(Map<String,UncookedDefinition> uncookedPatterns,
            String fromName, PatternReference def,
            List<String> stack) throws DefinitionParseException
    {
        final String toName = def.getText();
        // very first thing: maybe already resolved?
        LiteralPattern res = findPattern(toName);
        if (res != null) {
            return res;
        }

        // otherwise verify that we have no loop
        stack.add(fromName);
        if (stack.contains(toName) && !ExtractionResult.isReservedPattern(toName)) {
            def.reportError("Cyclic pattern reference to '%%%s' %s",
                    toName, _stackDesc("%", stack, toName));
            return null; // never gets here but FindBugs doesn't know it...
        }
        UncookedDefinition raw = uncookedPatterns.get(toName);
        LiteralPattern p;

        if (raw != null) {
            p = _resolvePattern(uncookedPatterns, toName, raw, stack);
            _patterns.put(toName, p);
        }
        else if (!ExtractionResult.isReservedPattern(toName))
        {
            def.reportError("Referencing non-existing pattern '%%%s' %s",
                    toName, _stackDesc("%", stack, toName));
            return null; // never gets here but FindBugs doesn't know it...
        }
        else  // only for reserved patterns defined inside template
            if (stack.contains(toName))
            {
                p = new LiteralPattern(def.getSource(), def._sourceOffset, String.format("[%s]", toName));
            }
            else {
                //InputLine marker = InputLine.create(null, def._sourceOffset, String.format("[%s]", toName));
                raw = new UncookedDefinition(null, toName, false, 0);
                p = _resolvePattern(uncookedPatterns, toName, raw, stack);
            }

        // but remove from stack
        stack.remove(stack.size()-1);
        return p;
    }

    /*
    /**********************************************************************
    /* Resolution: templates
    /**********************************************************************
     */

    /**
     * Method called to essentially flatten template definitions as much as possible,
     * although leaving nested structure for parametric templates.
     */
    public void resolveTemplates(UncookedDefinitions uncooked)  throws DefinitionParseException
    {
        Map<String,UncookedDefinition> uncookedTemplates = uncooked.getTemplates();
        for (UncookedDefinition template : uncookedTemplates.values()) {
            String name = template.getName();
            if (_templates.containsKey(name)) { // due to recursion, may have done it already
                continue;
            }
            CookedTemplate result = CookedTemplate.construct(template);
            _resolveTemplateContents(uncookedTemplates,
                    template.getName(), template.getParts(), result, null, name);
            _templates.put(name, result);
        }
    }

    private void _resolveTemplateContents(Map<String,UncookedDefinition> uncookedTemplates,
            String name, Iterable<DefPiece> toResolve, DefPieceAppendable result,
            List<String> stack, String topName)
        throws DefinitionParseException
    {
        for (DefPiece def : toResolve) {
            if (def instanceof LiteralPiece) { // literals fine as-is
                result.append(def);
            } else if (def instanceof PatternReference) {
                String patternRef = def.getText();
                LiteralPattern p = _patterns.get(patternRef);

                boolean isReserved = ExtractionResult.isReservedPattern(patternRef) || ExtractionResult.isCookedReservedPattern(p); // reserved pattern
                // Should never happen, should have been checked earlier...
                if (p == null && !isReserved)
                {
                    def.reportError("Referencing non-existing pattern '%%%s' from template '%s' %s",
                            patternRef, topName, _stackDesc("@", stack, result.getName()));
                    return; // never gets here but FindBugs doesn't know it...
                }

                LiteralPiece p_trail = null;
                String text_trail = "";
                boolean isFramed;
                if (p != null) {
                    if (isReserved) {
                        String text = p.getText();
                        String pattern_text;
                        int pos = 0;
                        int next_pos;
                        while ((next_pos = ExtractionResult.indexOfNextReservedPattern(text, pos)) != -1) {
                            pattern_text = ExtractionResult.nameOfNextReservedPattern(text, next_pos);
                            isFramed = next_pos > 0 && next_pos < text.length() - pattern_text.length() - 2 &&
                                    text.charAt(next_pos - 1) == '(' && text.charAt(next_pos + pattern_text.length() + 2) == ')';
                            if (isFramed)
                                next_pos--;

                            if (pos < next_pos)
                                result.append(new LiteralText(def.getSource(), def._sourceOffset, text.substring(pos, next_pos)));
                            result.append(new LiteralText(def.getSource(), def._sourceOffset, String.format("([%s])", pattern_text)));
                            _reserved_patterns.add(ExtractionResult.indexOfReservedPattern(pattern_text));
                            pos = next_pos + pattern_text.length() + 2;
                            if (isFramed)
                                pos += 2;
                        }
                        text_trail = text.substring(pos);
                    } else
                        p_trail = p;
                }
                else
                    text_trail = String.format("[%s]", patternRef);

                if (!text_trail.equals(""))
                    if (p == null)
                        p_trail = new LiteralPiece(def.getSource(), def._sourceOffset, text_trail);
                    else if (isReserved)
                        p_trail = new LiteralText(def.getSource(), def._sourceOffset, text_trail);

                if (p_trail != null)
                    result.append(p_trail);

                if (p == null)
                {
                    _reserved_patterns.add(ExtractionResult.indexOfReservedPattern(patternRef));
                    continue;
                }

            } else if (def instanceof TemplateReference) {
                TemplateReference refdTemplate = (TemplateReference) def;
                // Can only flatten non-parametric templates; others left as is
                if (refdTemplate.takesParameters()) {
                    result.append(refdTemplate);
                } else {
                    if (stack == null) {
                        stack = new LinkedList<>();
                    }
                    CookedTemplate tmpl = _resolveTemplateReference(uncookedTemplates,
                            name, refdTemplate, stack, topName);

                    // And for proper flattening, we'll just take out contents
                    for (DefPiece p : tmpl.getParts()) {
                        result.append(p);
                    }
                }
            } else if (def instanceof ExtractorExpression) {
                ExtractorExpression raw = (ExtractorExpression) def;
                ExtractorExpression resolved = raw.empty();
                if (stack == null) {
                    stack = new LinkedList<>();
                }
                // pass same name as we got, since we are not resolving other template
                _resolveTemplateContents(uncookedTemplates, name,
                        raw.getParts(), resolved, stack, topName);
                result.append(resolved);
            } else if (def instanceof TemplateParameterReference) {
                // 15-Dec-2015, tatu: Pass as-is, for now?
                result.append(def);
            } else {
                _unrecognizedPiece(def, "template definition '"+topName+"'");
            }
        }
    }

    private CookedTemplate _resolveTemplateReference(Map<String,UncookedDefinition> uncookedTemplates,
            String fromName, TemplateReference ref,
            List<String> stack, String topName) throws DefinitionParseException
    {
        final String toName = ref.getText();
        // very first thing: maybe already resolved?
        CookedTemplate res = _templates.get(toName);
        if (res != null) {
            return res;
        }
        // otherwise verify that we have no loop
        stack.add(fromName);
        if (stack.contains(toName)) {
            ref.reportError("Cyclic template reference to '%%%s' %s",
                    toName, _stackDesc("@", stack, toName));
            return null; // never gets here but FindBugs doesn't know it...
        }

        UncookedDefinition raw = uncookedTemplates.get(toName);
        //if (!ExtractionResult.isReservedPattern(toName)) {
            if (raw == null)
                if (!ExtractionResult.isReservedPattern(toName)) {
                    ref.reportError("Referencing non-existing template '%%%s' %s",
                            toName, _stackDesc("@", stack, toName));
                    return null; // never gets here but FindBugs doesn't know it...
                }
        /*}
        else  // reserved pattern
        {
            _reserved_patterns.add(ExtractionResult.indexOfReservedPattern(toName));
            return null;
        }*/
        CookedTemplate result = CookedTemplate.construct(raw);
        _resolveTemplateContents(uncookedTemplates,
                result.getName(), result.getParts(), result, stack, topName);

        _templates.put(toName, result);
        // but remove from stack
        stack.remove(stack.size()-1);
        return result;
    }

    /*
    /**********************************************************************
    /* Resolution: extraction flattening
    /**********************************************************************
     */

    /**
     * Final resolution method called when all named patterns, templates and inline extractors
     * have been resolved, flattened (to the degree they can be: extractors can be nested).
     * At this point translation into physical regexp input is needed.
     */
    public void resolveExtractions(UncookedDefinitions uncooked)
        throws DefinitionParseException
    {
        Map<String, UncookedExtraction> uncookedTemplates = uncooked.getExtractions();
        _extractions = new ArrayList<>();

        for (UncookedExtraction rawExtr : uncookedTemplates.values()) {
            UncookedDefinition rawTemplate = rawExtr.getTemplate();
            String name = rawTemplate.getName();
            CookedTemplate template = CookedTemplate.construct(rawTemplate);
            _resolveTemplateContents(Collections.<String,UncookedDefinition>emptyMap(),
                    rawTemplate.getName(), rawTemplate.getParts(), template, null, name);
            // Use set to efficiently catch duplicate extractor names
            Set<String> extractorNameSet = new LinkedHashSet<>();
            List<DefPiece> parts = new ArrayList<>();
            _resolveExtraction(template, parts, extractorNameSet, null);
            _extractions.add(new FlattenedExtraction(rawExtr, parts, extractorNameSet));
        }
    }

    private void _resolveExtraction(DefPieceAppendable template, List<DefPiece> parts,
            Collection<String> extractorNames,
            ParameterBindings activeBindings)
        throws DefinitionParseException
    {
        for (DefPiece part : template.getParts()) {
            if (_resolveLiteral(part, parts) || _resolveExtractor(part, parts, extractorNames, activeBindings)) {
                continue;
            }
            if (part instanceof TemplateReference) {
                _resolveTemplateRefFromExtraction((TemplateReference) part, parts,
                        extractorNames, activeBindings);
            } else if (part instanceof TemplateParameterReference) {
                // Should not occur at this point; should resolve via TemplateReference above
                TemplateParameterReference var = (TemplateParameterReference) part;
                part.reportError("Internal error: should not encounter template parameter %s#%d",
                        var.getParentId(), var.getPosition());
            } /*else if (part instanceof LiteralPattern && ExtractionResult.isReservedPattern(part.getText())) {
                continue;
            }*/ else {
                part.reportError("Internal error: unrecognized DefPiece %s", part.getClass().getName());
            }
        }
    }

    /**
     * Method called directly from template section of an extraction rule.
     */
    private void _resolveTemplateRefFromExtraction(TemplateReference ref, List<DefPiece> resultParts,
            Collection<String> extractorNames,
            ParameterBindings incomingBindings)
        throws DefinitionParseException
    {
        final InputLine src = ref.getSource();
        CookedTemplate template = _templates.get(ref.getName());
        if (template == null) { // should never occur but
            src.reportError(ref.getSourceOffset(),
                    "Internal error: reference to unknown template '@%s'", ref.getName());
        }

        // at this point, main-level template references have been flattened, but not
        // necessarily templates within parametric template parameter lists. So...
        ParameterBindings bindings = null;
        if (template.hasParameters()) {
            // Ok, then, create bindings. But first, ensure actual/expected member matches
            List<DefPiece> paramRefs = ref.getParameters();
            ParameterDeclarations paramDecls = template.getParameterDeclarations();
            final int pcount = paramDecls.size();
            if (paramRefs.size() != pcount) {
                src.reportError(ref.getSourceOffset(),
                        "Parameter mismatch: template '@%s' expects %d parameters; %d passed",
                            ref.getName(), pcount, paramRefs.size());
            }
    
            // For bindings need to resolve parameters
            bindings = new ParameterBindings(paramDecls);
            int i = 0;
            for (DefPiece piece : paramRefs) {
                char exp = paramDecls.getType(++i);
                if (!_paramCompatible(exp, piece)) {
                    src.reportError(ref.getSourceOffset(),
                            "Parameter mismatch: template '@%s' expects type '%c' parameter, got %s",
                                ref.getName(),  exp, piece.getClass().getName());
                }
                bindings.addBound(_resolveParameters(piece, resultParts, incomingBindings));
            }
        }
        _resolveExtractionParts(template.getParts(), resultParts, extractorNames, bindings);
    }

    private DefPiece _resolveParameters(DefPiece piece, List<DefPiece> parts,
            ParameterBindings bindings)
        throws DefinitionParseException
    {
        // Should only really get references to templates, parameters (variables)
        // and parameter references
        if (piece instanceof TemplateParameterReference) {
            TemplateParameterReference var = (TemplateParameterReference) piece;
            DefPiece v = bindings.getParameter(var.getPosition());
            if (v == null) { // sanity check: out of bound
                piece.reportError("Invalid parameter variable reference @%d; template has %d parameters",
                        var.getPosition(), bindings.size());
            }
            return v;
        }
        if (piece instanceof TemplateReference) {
            TemplateReference templ = (TemplateReference) piece;
            List<DefPiece> params = templ.getParameters();
            if (params == null) {
                return templ;
            }
            List<DefPiece> newParams = new ArrayList<>();
            for (DefPiece p : params) {
                newParams.add(_resolveParameters(p, parts, bindings));
            }
            return templ.withParameters(newParams);
        }
        if (piece instanceof ExtractorExpression) {
            ExtractorExpression extr = (ExtractorExpression) piece;
            List<DefPiece> newParts = new ArrayList<>();
            for (DefPiece p : extr.getParts()) {
                newParts.add(_resolveParameters(p, parts, bindings));
            }
            extr = extr.withParts(newParts);
            return extr;
        }
        piece.reportError("Internal error: unexpected template parameter type %s", piece.getClass().getName());
        return piece;
    }

    private void _resolveExtractionParts(Iterable<DefPiece> inputParts, List<DefPiece> resultParts,
            Collection<String> extractorNames,
            ParameterBindings activeBindings)
        throws DefinitionParseException
    {
        for (DefPiece part : inputParts) {
            // First: parameters just expand to a single other thing
            if (part instanceof TemplateParameterReference) {
                TemplateParameterReference paramRef = (TemplateParameterReference) part;
                int pos = paramRef.getPosition();
                if (activeBindings == null){
                    part.reportError("Invalid parameter variable reference @%d; template takes no parameters", pos);
                }
                DefPiece param = (activeBindings == null) ? null : activeBindings.getParameter(pos);
                if (param == null) {
                    part.reportError("Invalid parameter variable reference @%d; template takes %d parameters",
                            pos, activeBindings.size());
                }
                part = param;
                // fall-through for further processing
            }

            if (_resolveLiteral(part, resultParts)
                    || _resolveExtractor(part, resultParts, extractorNames, activeBindings)) {
                continue;
            }
            
            if (part instanceof TemplateReference) {
                _resolveTemplateRefFromExtraction((TemplateReference) part, resultParts,
                        extractorNames, activeBindings);
                continue;
            }
            part.reportError("Internal error: unrecognized DefPiece %s", part.getClass().getName());
        }
    }

    private boolean _resolveExtractor(DefPiece part, List<DefPiece> parts,
            Collection<String> extractorNames,
            ParameterBindings activeBindings)
        throws DefinitionParseException
    {
        if (!(part instanceof ExtractorExpression)) {
            return false;
        }
        ExtractorExpression extr = (ExtractorExpression) part;

        // Possibly parametric?
        int pos = extr.getPosition();
        if (pos >= 0) {
            DefPiece p = activeBindings.getParameter(pos);
            if (!(p instanceof ExtractorExpression)) {
                part.reportError("Internal error: unexpected extractor parameter of type %s (expecting ExtractorExpression)",
                        p.getClass().getName());
            }
            ExtractorExpression ee = (ExtractorExpression) p;
            if (ee.isPositional()) {
                part.reportError("Internal error: positional extractor parameter (%d) resolves to another positional (%d)",
                        pos, ee.getPosition());
            }
            extr = extr.withName(ee.getName());
        }
        if (!extractorNames.add(extr.getName())) { // not allowed
            part.reportError("Duplicate extractor name ($%s)", extr.getName());
        }

        // But now need to resolve contents, re-create container itself
        List<DefPiece> newParts = new ArrayList<>();
        _resolveExtractionParts(extr.getParts(), newParts, extractorNames, activeBindings);
        parts.add(extr.withParts(newParts));
        return true;
    }

    private boolean _resolveLiteral(DefPiece part, List<DefPiece> parts)
        throws DefinitionParseException
    {
        if ((part instanceof LiteralText) || (part instanceof LiteralPattern)) {
            parts.add(part);
            return true;
        }
        if (ExtractionResult.isCookedReservedPattern(part))
        {
            parts.add(part);
            return true;
        }
        if (part instanceof PatternReference) {
            String patternRef = part.getText();
            LiteralPattern p = _patterns.get(patternRef);
            // Should never happen, should have been checked earlier...
            if (p == null) {
                throw new IllegalStateException(String.format(
                        "Internal error: non-existing pattern '%%%s', should have been caught earlier",
                        patternRef));
            }
            parts.add(p);
            return true;
        }
        return false;
    }

    private boolean _paramCompatible(char exp, DefPiece p)
    {
        switch (exp) {
        case '@':
            return (p instanceof TemplateReference);
        case '$':
            return (p instanceof ExtractorExpression);
        default:
            throw new IllegalStateException("Internal error: unrecognized template parameter type '"
                    +exp+"' (for actual parameter type of "+p.getClass().getName()+")");
        }
    }

    /*
    /**********************************************************************
    /* Helper methods
    /**********************************************************************
     */

    private String _stackDesc(String marker, List<String> stack, String last) {
        if (stack == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(100);
        sb.append('(');
        for (String str : stack) {
            sb.append(marker).append(str);
            sb.append("->");
        }
        sb.append(marker).append(last);
        sb.append(')');
        return sb.toString();
    }

    private void _unrecognizedPiece(DefPiece def, String type) throws DefinitionParseException{
        def.getSource().reportError(0, "Internal error: unexpected definition type %s when resolving %s",
                def.getClass().getName(), type);
    }
}
