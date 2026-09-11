package com.diegonmarcos.cloudnotes.format.plaintext;
/*#######################################################
 *
 *   Maintained 2018-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/

import android.util.Log;

import com.diegonmarcos.cloudnotes.format.plaintext.highlight.CodeTheme;
import com.diegonmarcos.cloudnotes.format.plaintext.highlight.HighlightConfigLoader;
import com.diegonmarcos.cloudnotes.format.plaintext.highlight.Syntax;
import com.diegonmarcos.cloudnotes.frontend.textview.SyntaxHighlighterBase;
import com.diegonmarcos.cloudnotes.model.AppSettings;

import java.util.ArrayList;
import java.util.HashMap;

public class PlaintextSyntaxHighlighter extends SyntaxHighlighterBase {
    public final static HighlightConfigLoader configLoader = new HighlightConfigLoader();
    private ArrayList<Syntax.Rule> rules;
    private HashMap<String, CodeTheme.ThemeValue> styles;

    public PlaintextSyntaxHighlighter(AppSettings as) {
        super(as);
    }

    public PlaintextSyntaxHighlighter(AppSettings appSettings, String extension) {
        super(appSettings);

        Syntax syntax = null;
        try {
            syntax = configLoader.getSyntax(appSettings.getContext(), extension);
        } catch (Exception e) {
            Log.e(getClass().getName(), e.toString());
        }
        if (syntax != null) {
            rules = syntax.rules;
            CodeTheme codeTheme = configLoader.getTheme(appSettings.getContext(), "default");
            if (codeTheme != null) {
                styles = codeTheme.styles;
            }
        }
    }

    @Override
    protected void generateSpans() {
        createTabSpans(_tabSize);
        createUnderlineHexColorsSpans();
        createSmallBlueLinkSpans();

        if (rules == null || styles == null) {
            return;
        }

        for (Syntax.Rule rule : rules) {
            CodeTheme.ThemeValue style = styles.get(rule.type);
            if (style != null) {
                createColorSpanForMatches(rule.getPattern(), style.getColor());
            }
        }
    }
}
