/* 
 * Copyright (c) 2016, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license. 
 * For full license text, see LICENSE.txt file in the repo root  or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.gorp;

import com.salesforce.gorp.autom.PolyMatcher;
import com.salesforce.gorp.jdkre.JDKRegexpCookedExtraction;
import com.salesforce.gorp.jdkre.JDKRegexpExtractionCooker;
import com.salesforce.gorp.model.*;
import com.salesforce.gorp.util.RegexHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Processor built from a definition that is used to actually extract
 * information out of input lines.
 *<p>
 * Instances are fully thread-safe and may be used concurrently.
 */
public class Gorp
{
    /**
     * Multi-expression matcher that is capable of figuring out which extraction
     * rules, if any, matched. This is needed to know which actual extraction-based
     * regular expression to use for actual data extraction.
     */
    protected final PolyMatcher _matcher;

    protected final CookedExtraction[] _extractions;

    protected List<Integer> _reserved_extractions;

    protected Gorp(PolyMatcher matcher, CookedExtraction[] extr) {
        _matcher = matcher;
        _extractions = extr;
    }

    protected Gorp(PolyMatcher matcher, CookedExtraction[] extr, List<Integer> reserved_extractions) {
        this(matcher, extr);
        _reserved_extractions = reserved_extractions;
    }

    public static Gorp construct(CookedDefinitions defs)
        throws DefinitionParseException
    {
        return construct(defs, JDKRegexpExtractionCooker.instance());
    }
    
    /**
     * Main factory method that will build {@link Gorp} out of fully
     * resolved {@link CookedDefinitions}.
     */
    public static Gorp construct(CookedDefinitions defs, ExtractionCooker cooker)
        throws DefinitionParseException
    {
        List<CookedExtraction> cookedExtr = new ArrayList<>();
        List<FlattenedExtraction> extractions = defs.getExtractions();
        List<String> automatonInputs = new ArrayList<>(extractions.size());
        // Use set to efficiently catch duplicate extractor names

        for (int i = 0, end = extractions.size(); i < end; ++i) {
            FlattenedExtraction ext = extractions.get(i);

            StringBuilder automatonInput = new StringBuilder();
            StringBuilder regexpInput = new StringBuilder();
            for (DefPiece part : ext) {
                _buildExtractor(automatonInput, regexpInput, cooker, part);
            }
    
            // last null -> no bindings from within extraction declaration
            automatonInputs.add(automatonInput.toString());

            final String regexpSource = regexpInput.toString();
            final int index = cookedExtr.size();
            try {
                cookedExtr.add(cooker.cook(index, regexpSource, ext));
            } catch (Exception e) { // should never occur. Probably does, so...
                ext.iterator().next()
                    .reportError("Internal problem: invalid regular expression segment, problem: %s", e.getMessage());
            }

        }
        // With that, can try constructing multi-matcher
        PolyMatcher poly = null;
        try {
            poly = PolyMatcher.create(automatonInputs);
        } catch (Exception e) {
            DefinitionParseException pe = DefinitionParseException.construct(
                    "Internal error: problem with PolyMatcher construction: "+ e.getMessage(),
                    null, 0);
            pe.initCause(e);
            throw pe;
        }
        if (defs.getReservedPatterns().size() == 0)
            return new Gorp(poly, cookedExtr.toArray(new CookedExtraction[cookedExtr.size()]));
        else
            return new Gorp(poly, cookedExtr.toArray(new CookedExtraction[cookedExtr.size()]), defs.getReservedPatterns());
    }

    private static void _buildExtractor(StringBuilder automatonInput, StringBuilder regexpInput,
            ExtractionCooker cooker, DefPiece part)
        throws DefinitionParseException
    {
        if (part instanceof LiteralPattern) {
            final String text = part.getText();
            try {
                RegexHelper.massageRegexpForAutomaton(text, automatonInput);
                cooker.appendPattern(text, regexpInput);
            } catch (Exception e) {
                part.reportError("Invalid pattern definition, problem (%s): %s",
                        e.getClass().getName(), e.getMessage());
            }
            return;
        }
        boolean isLiteral = part instanceof LiteralText;
        boolean isReserved = false;
        if (!isLiteral)
            isReserved = ExtractionResult.isCookedReservedPattern(part);
        if (isLiteral || isReserved) {
            final String literal = part.getText();
            if (!isReserved)
                RegexHelper.quoteLiteralAsRegexp(literal, automatonInput);
            cooker.appendLiteral(literal, regexpInput);
            return;
        }
        if (part instanceof ExtractorExpression) {
            // not sure if we need to enclose it for Automaton, but shouldn't hurt
            automatonInput.append('(');
            cooker.appendStartExpression(regexpInput);
            // and for "regular" Regexp package, must add to get group
            ExtractorExpression extr = (ExtractorExpression) part;
            for (DefPiece p : extr.getParts()) {
                _buildExtractor(automatonInput, regexpInput, cooker, p);
            }
            automatonInput.append(')');
            cooker.appendFinishExpression(regexpInput);
            return;
        }
        part.reportError("Unrecognized DefPiece in FlattenedExtraction: %s", part.getClass().getName());
    }
    
    public List<CookedExtraction> getExtractions() {
        return Arrays.asList(_extractions);
    }

    public PolyMatcher getMatcher() {
        return _matcher;
    }

    /**
     * Match method that expects the first full match to work as expected,
     * evaluate extraction and return the result. If the first match
     * by multi-matcher fails for some reason (internal problem with
     * translations), a {@link ExtractionException} will be thrown.
     */
    public ExtractionResult extract(String input) throws ExtractionException {
        return extract(input, false, false);
    }

    /**
     * Match method that tries potential matches in order, returning first
     * that fully works. Should only be used if there is fear that sometimes
     * matchers are not properly translated; but if so, it is preferable to
     * get a lower-precedence match, or possible none at all.
     */
    public ExtractionResult extractSafe(String input) throws ExtractionException {
        return extract(input, true, false);
    }

    public ExtractionResult extractAllFound(String input) throws ExtractionException {
        return extract(input, true, true);
    }

    public ExtractionResult extract(String input, boolean allowFallbacks) throws ExtractionException
    {
        return extract(input, allowFallbacks, false);
    }

    public ExtractionResult extract(String input, boolean allowFallbacks, boolean allowSubsequences) throws ExtractionException
    {
        int[] matchIndexes;
        int matchIndex;
        int count = 0;

        if (!allowSubsequences)
        {
            matchIndexes = _matcher.match(input);
            if (matchIndexes.length == 0) {
                return null;
            }

            // First one ought to suffice, try that first
            matchIndex = matchIndexes[0];
        }
        else {
            matchIndex = 0;
            matchIndexes = new int [0];
            count = _extractions.length;
        }

        CookedExtraction extr;
        ExtractionResult result, resBuffer = null;

        do {
            extr = _extractions[matchIndex];

            if (allowSubsequences) {
                JDKRegexpCookedExtraction _extr = (JDKRegexpCookedExtraction)extr;
                Pattern _regexp = _extr.getRegexp();

                //String [] tempExtrArr = _regexp.pattern().split(ExtractionResult.getReservedTemplatesList("$0", "|"));

                String extrText = _regexp.pattern();
                String reserved_substr;
                List<Pattern> extrList = new ArrayList<>();
                String subtext;
                int pos = 0;
                int index;
                boolean isScreened;
                if (_reserved_extractions != null) {
                    for (index = 0; index < _reserved_extractions.size(); index++) {
                        String pattern_text = ExtractionResult.reservedPatternNames.get(_reserved_extractions.get(index));
                        reserved_substr = String.format("\\(\\[%s\\]\\)", pattern_text);
                        int next_pos = extrText.indexOf(reserved_substr, pos);

                        if (next_pos == -1) {
                            extrList.add(Pattern.compile(extrText.substring(pos)));
                            break;
                        } else {

                            subtext = extrText.substring(pos, next_pos);
                            int bracket_pos;
                            int bracket_pos2 = -1;

                            do {
                                bracket_pos = subtext.lastIndexOf('(');
                                isScreened = bracket_pos > 0 && subtext.charAt(bracket_pos - 1) == '\\';

                                if (!isScreened && bracket_pos != -1) {
                                    do {
                                        bracket_pos2 = subtext.lastIndexOf(')');
                                       }
                                    while (bracket_pos2 > bracket_pos && subtext.charAt(bracket_pos2 - 1) == '\\');

                                    if (bracket_pos2 > bracket_pos)
                                        break;
                                    subtext = extrText.substring(pos, pos + bracket_pos);
                                }
                            }
                            while (bracket_pos != -1 && (isScreened || bracket_pos2 > bracket_pos));

                            if (bracket_pos2 != -1 && bracket_pos2 < bracket_pos)
                            {
                                bracket_pos2 = next_pos + reserved_substr.length();
                                do {
                                    bracket_pos2 = extrText.indexOf(')', bracket_pos2);
                                }
                                while (bracket_pos2 != -1 && extrText.charAt(bracket_pos2 - 1) == '\\');
                            }
                            else
                                bracket_pos2 = -1;

                            if (bracket_pos2 != -1)
                            {
                                if (bracket_pos < next_pos - pos - 1)
                                    subtext = extrText.substring(pos, next_pos) + ')';
                                else
                                    subtext = extrText.substring(pos, next_pos - 1);
                                extrText = extrText.substring(0, bracket_pos2) + extrText.substring(bracket_pos2 + 1);
                                //next_pos++;
                            }
                            else
                                subtext = extrText.substring(pos, next_pos);

                            extrList.add(Pattern.compile(subtext));
                            //String str = reserved_substr.substring(2, reserved_substr.length() - 2);
                            extrList.add(Pattern.compile(reserved_substr.substring(2, reserved_substr.length() - 2)));
                            //extrList.add(Pattern.compile(String.format("\\(\\[%d\\]\\)", index)));
                            pos = next_pos;
                        }
                        pos += reserved_substr.length();
                    }
                    if (pos < extrText.length())
                        _regexp = Pattern.compile(extrText.substring(pos));
                    else
                        _regexp = null;
                }

                if (_regexp != null)
                    extrList.add(_regexp);

                result = _extr.matchMultiEntries(input, extrList, _reserved_extractions);

                if (resBuffer != null) {
                    if (result != null)
                        resBuffer = resBuffer.appendNewInstance(result);
                    result = resBuffer;
                } else
                    resBuffer = result;
            }
            else {
                result = extr.match(input);
            }
            if (result != null)
                count -= result._length;
            else
                count--;
            matchIndex++;
        } while (matchIndex < count);

        if (result != null) {
            return result;
        }
        // More than one? Should we throw an exception or play safe?
        if (!allowFallbacks && !allowSubsequences) {
            throw new ExtractionException(input,
                    String.format("Internal error: high-level match for extraction #%d (%s) failed to match generated regexp: %s",
                            matchIndex, extr.getName(), extr.getRegexpDesc()));
        }

        if (!allowSubsequences) {
            for (int i = 1, end = matchIndexes.length; i < end; ++i) {
                result = _extractions[matchIndex].match(input);
                if (result != null) {
                    return result;
                }
            }
        }

        // nothing matches, despite initially seeming they would?
        return null;
    }
}
