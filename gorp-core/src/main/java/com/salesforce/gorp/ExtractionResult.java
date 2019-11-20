/* 
 * Copyright (c) 2016, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license. 
 * For full license text, see LICENSE.txt file in the repo root  or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.gorp;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.salesforce.gorp.model.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Result gotten by matching an input line against extraction rules.
 * If multiple matches would occur, highest matching one (one defined
 * first in the source definition) is used.
 */
public class ExtractionResult
{
    protected final String _id;

    protected final String _input;

    protected final CookedExtraction _matchedExtraction;

    protected final String[] _extractorNames;
    protected final String[] _extractedValues;

    protected final int _length;

    protected final boolean _allowDuplicates;

    protected static List<String> reservedPatternNames = Arrays.asList(new String[] { "all_before", "all_after" });

    public ExtractionResult(String id, String input, CookedExtraction extr,
            String[] names, String[] values, int length, boolean allowDuplicates)
    {
        _id = id;
        _input = input;
        _matchedExtraction = extr;
        _extractorNames = names;
        _extractedValues = values;
        _length = length;
        _allowDuplicates = allowDuplicates;
    }

    public ExtractionResult(String id, String input, CookedExtraction extr,
                            String[] names, String[] values, int length)
    {
        this(id, input, extr, names, values, length, false);
    }

    private ExtractionResult addMissingKeys(ExtractionResult dest, ExtractionResult source)
    {
        List <String> sourceNamesList = Arrays.asList(source._extractorNames);
        List <String> sourceValuesList = Arrays.asList(source._extractedValues);
        List <String> destNamesList = Arrays.asList(dest._extractorNames);
        List <String> destValuesList = Arrays.asList(dest._extractedValues);

        List <String> newFormedNamesList = new ArrayList<>();
        List <String> newFormedValuesList = new ArrayList<>();

        boolean insert = false;
        int index = 0;
        int pos, offset;
        String value;

        newFormedNamesList.addAll(destNamesList);
        newFormedValuesList.addAll(destValuesList);

        for (String extrName : sourceNamesList) {

            pos = newFormedNamesList.indexOf(extrName);
            if (pos != index) {

                insert = true;
                newFormedNamesList.add(index, extrName);
                offset = 0;

                for (int i = 0; i < dest._length; ++i) {
                    if (pos != -1) {
                        // (pos > index)
                        value = newFormedValuesList.get(offset + pos);
                        newFormedValuesList.remove(offset + pos);
                    }
                    else
                        value = "";

                    if (offset + index < newFormedValuesList.size())
                        newFormedValuesList.add(offset + index, value);
                    else
                        newFormedValuesList.add(value);

                    offset += newFormedNamesList.size();
                }
            }
            index++;
        }

        if (insert)
            return new ExtractionResult(dest._id, dest._input, dest._matchedExtraction, newFormedNamesList.toArray(new String [0]),
                newFormedValuesList.toArray(new String [0]), dest._length);
        return dest;
    }


    public ExtractionResult appendNewInstance(ExtractionResult _new)
    {
        //Set<String> set = new HashSet<String>(Arrays.asList(_new._extractorNames));

        ExtractionResult source, dest;
        source = _new;
        dest = this;

        do {
            source  = addMissingKeys(source, dest);
            dest = addMissingKeys(dest, source);

        }
        while (source._extractorNames.length != dest._extractorNames.length);

        int length = _length + _new._length;
        List <String> newFormedValuesList = new ArrayList<>();
        newFormedValuesList.addAll(Arrays.asList(dest._extractedValues));
        newFormedValuesList.addAll(Arrays.asList(source._extractedValues));

        return new ExtractionResult(this._id, this._input, this._matchedExtraction, source._extractorNames,
                newFormedValuesList.toArray(new String [0]), length);
    }

    public String getId() { return _id; }
    public String getInput() { return _input; }
    public CookedExtraction getMatchedExtraction() { return _matchedExtraction; }

    public Map<String,Object> getExtra() { return _matchedExtraction.getExtra(); }

    /**
     * Method to call to get extracted results (including values to append, if any)
     * as a {link java.util.Map}.
     * Equivalent to calling
     *<pre>
     *    asMap(null);
     *</pre>
     * so that "id" of the matching extraction is not included as a property
     */
    public Multimap<String,Object> asMap() {
        return asMap(null);
    }
    
    /**
     * Method to call to get extracted results (including values to append, if any)
     * as a {link java.util.Map}.
     * 
     * @param idAs Optional property to use for id of the matched extraction; if null,
     *     name is not added as a property
     */
    public Multimap<String,Object> asMap(String idAs)
    {
        Map<String,Object> extra = getExtra();

        Multimap<String,Object> result = LinkedHashMultimap.create();

        /*Map<String,Object> extra = getExtra();
        int size = _extractedValues.length;
        if (extra != null) {
            size += extra.size();
        }*/

        //LinkedHashMap<String,Object> result = new LinkedHashMap<>(size);

        // Start with id; add actual matches, append appendables
        if (idAs != null) {
            result.put(idAs, _id);
        }

        /*for (int i = 0, end = _extractedValues.length; i < end; ++i) {
            result.put(_extractorNames[i], _extractedValues[i]);
        }*/

        int index = 0;
        for (int i = 0; i < _length; ++i) {
            for (int j = 0, end = _extractorNames.length; j < end; ++j) {
                result.put(_extractorNames[j], _extractedValues[index + j]);
            }
            index += _extractorNames.length;
        }

        if (extra != null) {
            // result.putAll(extra);
            for (String key : extra.keySet()) {
                result.put(key, extra.get(key));
            }
        }
        return result;
    }

    public Multimap<String,List <String>> asHashMap()
    {
        Map<String,Object> extra = getExtra();

        Multimap<String, List <String>> result = LinkedHashMultimap.create();
        List <String> values;

        for (int i = 0; i < _extractorNames.length; ++i) {
            values = new ArrayList<String>();
            for (int j = 0; j < _length; ++j) {
                values.add(_extractedValues[j * _extractorNames.length  + i]);
            }
            result.put(_extractorNames [i], values);
        }

        if (extra != null) {
            // result.putAll(extra);
            for (String key : extra.keySet()) {
                values = new ArrayList<String>();
                values.add((String)extra.get(key));
                result.put(key, values);
            }
        }

        return result;
    }

    public static String getReservedPatternsList(String mask, String delim)
    {
        StringBuffer _str = new StringBuffer();
        int index = 0;
        for (String pattern : reservedPatternNames)
        {
            _str.append(String.format(mask, pattern));
            //_str.append(String.format("[%s]", pattern));
            if (index++ < reservedPatternNames.size() - 1)
                _str.append(delim);
        }
        return _str.toString();
    }

    public static boolean isReservedPattern(String name)
    {
        return reservedPatternNames.contains(name);
    }

    public static int indexOfReservedPattern(String name)
    {
        return reservedPatternNames.indexOf(name);
    }

    public static boolean isCookedReservedPattern(DefPiece pattern)
    {
        if (!(pattern instanceof LiteralPiece))
            return false;

        String name = pattern.getText();
        if (pattern instanceof LiteralPattern) {
            return name.contains(getReservedPattern(0)) || name.contains(getReservedPattern(1));
        }

        return isCookedReservedPattern(name);
    }

    public static boolean isCookedReservedPattern(String name)
    {
        return name.equals(getReservedPattern(0)) || name.equals(getReservedPattern(1));
    }

    public static int indexOfNextReservedPattern(String text, int pos)
    {
        int next_pos, cur_pos;
        next_pos = -1;
        for (int index = 0; index < reservedPatternNames.size(); index++)
        {
            String name = getReservedPattern(index);
            cur_pos = text.indexOf(name, pos);
            if (cur_pos != -1 && (cur_pos < next_pos || next_pos == -1))
                next_pos = cur_pos;
        }
        return next_pos;
    }

    public static String nameOfNextReservedPattern(String text, int pos)
    {
        for (int index = 0; index < reservedPatternNames.size(); index++)
        {
            String name = getReservedPattern(index);
            if (text.startsWith(name, pos))
                return reservedPatternNames.get(index);
        }

        return "";
    }

    public static String[] splitByCookedReservedPattern(String name)
    {
        String[] slice = name.split(String.format("\\[%s|%s\\]", reservedPatternNames.get(0), reservedPatternNames.get(1)));
        return slice;
    }

    private static String getReservedPattern(int index)
    {
        return "[" + reservedPatternNames.get(index) + "]";
    }
}
