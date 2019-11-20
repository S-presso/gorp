package com.salesforce.gorp.jdkre;

import com.salesforce.gorp.ExtractionResult;
import com.salesforce.gorp.io.InputLine;
import com.salesforce.gorp.model.CookedExtraction;
import com.salesforce.gorp.model.FlattenedExtraction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class JDKRegexpCookedExtraction
    extends CookedExtraction
{
    protected final Pattern _regexp;

    protected int pos = -1;

    protected JDKRegexpCookedExtraction(InputLine source, String name,
            int index, Map<String,Object> append,
            Pattern regexp, String regexpSource, String[] extractorNames)
    {
        super(source, name, index, append, regexpSource, extractorNames);
        _regexp = regexp;
    }

    public static JDKRegexpCookedExtraction construct(int index, FlattenedExtraction src,
            Pattern regexp, String regexpSource, List<String> extractorNamesList)
    {
        String[] extrNames = extractorNamesList.toArray(new String[extractorNamesList.size()]);
        return new JDKRegexpCookedExtraction(src.getSource(), src.getName(),
                index, src.getAppends(),
                regexp, regexpSource, extrNames);
    }

    @Override
    public ExtractionResult match(String input) {
        Matcher m = _regexp.matcher(input);
        return m.matches() ? _constructMatch(input, m) : null;
    }

    @Override
    public Pattern getRegexp() {
        return _regexp;
    }

    @Override
    public String getRegexpDesc() {
        return _regexp.pattern();
    }

    public ExtractionResult matchMultiEntries(String input, List<Pattern> extrList, List<Integer> reserved_extractions) {
        return _constructMatch(input, extrList, reserved_extractions);
    }

    private boolean canJumpToNextPart(Matcher m)
    {
        boolean found = (pos == -1) ? m.find() : m.find(pos);
        if (found)
            pos = m.end();
        return found;
    }

    protected ExtractionResult _constructMatch(String input, Matcher m)
    {
        final int count = m.groupCount();
        String[] values = new String[count];
        for (int i = 0; i < count; ++i) {
            values[i] = m.group(i+1);
        }
        return constructMatch(input, values, 1);
    }

    protected ExtractionResult _constructMatch(String input, List<Pattern> extrList, List<Integer> reserved_extractions)
    {
        List<String> valuesList = null;
        List<String> prev_valuesList;
        List<String> appended_valuesList = new ArrayList<String>();

        int length = 0;

        ExtractionResult result, resBuffer = null;
        boolean regular = true;
        boolean no_more_found = false;

        int index;
        int prev_pos;
        int patternIndex;
        boolean skipMode = false;
        boolean mustBypass = false;

        Matcher m;
        Pattern part;
        String extr_entry;

        do {
            prev_valuesList = valuesList;
            valuesList = new ArrayList<String>();

            for (index = 0; index < extrList.size();) {

                patternIndex = -1;

                do {
                    part = extrList.get(index);

                    if (reserved_extractions != null) {
                        extr_entry = part.pattern().replace("\\", "");
                        mustBypass = reserved_extractions != null && ExtractionResult.isCookedReservedPattern(extr_entry);
                        if (mustBypass) {
                            if (!skipMode)
                                skipMode = true;

                            extr_entry = extr_entry.substring(1, extr_entry.length() - 1);
                            patternIndex = ExtractionResult.indexOfReservedPattern(extr_entry);
                            /*switch (patternIndex) {
                                case 0:
                                    mustBypass = true; //fixCurrentPosition(m);
                                    break;
                                case 1:
                                    //prev_pos = pos;
                                    partInserted = true;
                                    break;
                                //continue;
                            }*/
                        }
                    }
                    index++;
                } while (mustBypass && index < extrList.size());

                prev_pos = pos != -1 ? pos : 0;
                do {
                    m = part.matcher(input);

                    no_more_found = !canJumpToNextPart(m);
                    if (no_more_found)
                        break;

                    int count = !skipMode ? m.groupCount() : 1;

                    for (int i = 0; i < count; ++i) {
                        if (patternIndex == 1)
                            extr_entry = input.substring(prev_pos, pos);
                        else
                            extr_entry = "";

                        if (m.groupCount() < 1)
                        {
                            if (patternIndex == 1)
                                valuesList.add(extr_entry);
                            break;
                        }

                        extr_entry += m.group(i + 1);
                        valuesList.add(extr_entry);
                    }
                } while (false);

                if (no_more_found)
                    break;
            }

            if (no_more_found)
                break;

            if (prev_valuesList != null && regular)
            {
                if (valuesList.size() == prev_valuesList.size())
                    appended_valuesList.addAll(valuesList);
                else
                {
                    regular = false;
                    if (!appended_valuesList.isEmpty())
                        resBuffer = constructMatch(input, appended_valuesList.toArray(new String[0]), length);
                    //appended_valuesList = new ArrayList<String>();
                    length = 0;
                }
            }
            else
                appended_valuesList = valuesList;

            if (!regular)
            {
                result = constructMatch(input, valuesList.toArray(new String[0]), 1);
                if (resBuffer != null)
                    resBuffer = resBuffer.appendNewInstance(result);
                else
                    resBuffer = result;
            }
            else
                length++;
        }
        while (true);

        if (regular)
            return constructMatch(input, appended_valuesList.toArray(new String[0]), length);

        return resBuffer;


        /*final int count = m.groupCount();
        String[] values = new String[count];
        for (int i = 0; i < count; ++i) {
            values[i] = m.group(i+1);
        }
        return constructMatch(input, values, 1);*/
    }
}
