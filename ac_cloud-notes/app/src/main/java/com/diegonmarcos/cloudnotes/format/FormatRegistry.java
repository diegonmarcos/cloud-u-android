/*#######################################################
 *
 *   Maintained 2018-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package com.diegonmarcos.cloudnotes.format;

import android.content.Context;
import android.text.InputFilter;
import android.text.TextWatcher;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.diegonmarcos.cloudnotes.R;
import com.diegonmarcos.cloudnotes.format.asciidoc.AsciidocActionButtons;
import com.diegonmarcos.cloudnotes.format.asciidoc.AsciidocSyntaxHighlighter;
import com.diegonmarcos.cloudnotes.format.asciidoc.AsciidocTextConverter;
import com.diegonmarcos.cloudnotes.format.binary.EmbedBinaryTextConverter;
import com.diegonmarcos.cloudnotes.format.csv.CsvSyntaxHighlighter;
import com.diegonmarcos.cloudnotes.format.csv.CsvTextConverter;
import com.diegonmarcos.cloudnotes.format.keyvalue.KeyValueSyntaxHighlighter;
import com.diegonmarcos.cloudnotes.format.keyvalue.KeyValueTextConverter;
import com.diegonmarcos.cloudnotes.format.markdown.MarkdownActionButtons;
import com.diegonmarcos.cloudnotes.format.markdown.MarkdownReplacePatternGenerator;
import com.diegonmarcos.cloudnotes.format.markdown.MarkdownSyntaxHighlighter;
import com.diegonmarcos.cloudnotes.format.markdown.MarkdownTextConverter;
import com.diegonmarcos.cloudnotes.format.orgmode.OrgmodeActionButtons;
import com.diegonmarcos.cloudnotes.format.orgmode.OrgmodeReplacePatternGenerator;
import com.diegonmarcos.cloudnotes.format.orgmode.OrgmodeSyntaxHighlighter;
import com.diegonmarcos.cloudnotes.format.orgmode.OrgmodeTextConverter;
import com.diegonmarcos.cloudnotes.format.plaintext.PlaintextActionButtons;
import com.diegonmarcos.cloudnotes.format.plaintext.PlaintextSyntaxHighlighter;
import com.diegonmarcos.cloudnotes.format.plaintext.PlaintextTextConverter;
import com.diegonmarcos.cloudnotes.format.todotxt.TodoTxtActionButtons;
import com.diegonmarcos.cloudnotes.format.todotxt.TodoTxtAutoTextFormatter;
import com.diegonmarcos.cloudnotes.format.todotxt.TodoTxtSyntaxHighlighter;
import com.diegonmarcos.cloudnotes.format.todotxt.TodoTxtTextConverter;
import com.diegonmarcos.cloudnotes.format.wikitext.WikitextActionButtons;
import com.diegonmarcos.cloudnotes.format.wikitext.WikitextReplacePatternGenerator;
import com.diegonmarcos.cloudnotes.format.wikitext.WikitextSyntaxHighlighter;
import com.diegonmarcos.cloudnotes.format.wikitext.WikitextTextConverter;
import com.diegonmarcos.cloudnotes.frontend.textview.AutoTextFormatter;
import com.diegonmarcos.cloudnotes.frontend.textview.ListHandler;
import com.diegonmarcos.cloudnotes.frontend.textview.SyntaxHighlighterBase;
import com.diegonmarcos.cloudnotes.model.AppSettings;
import com.diegonmarcos.cloudnotes.model.Document;
import com.diegonmarcos.cloudnotes.opoc.util.GsFileUtils;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class FormatRegistry {
    public static final int FORMAT_UNKNOWN = 0;
    public static final int FORMAT_WIKITEXT = R.string.action_format_wikitext;
    public static final int FORMAT_MARKDOWN = R.string.action_format_markdown;
    public static final int FORMAT_CSV = R.string.action_format_csv;
    public static final int FORMAT_PLAIN = R.string.action_format_plaintext;
    public static final int FORMAT_ASCIIDOC = R.string.action_format_asciidoc;
    public static final int FORMAT_TODOTXT = R.string.action_format_todotxt;
    public static final int FORMAT_KEYVALUE = R.string.action_format_keyvalue;
    public static final int FORMAT_EMBEDBINARY = R.string.action_format_embedbinary;
    public static final int FORMAT_ORGMODE = R.string.action_format_orgmode;

    public final static MarkdownTextConverter CONVERTER_MARKDOWN = new MarkdownTextConverter();
    public final static WikitextTextConverter CONVERTER_WIKITEXT = new WikitextTextConverter();
    public final static TodoTxtTextConverter CONVERTER_TODOTXT = new TodoTxtTextConverter();
    public final static KeyValueTextConverter CONVERTER_KEYVALUE = new KeyValueTextConverter();
    public final static CsvTextConverter CONVERTER_CSV = new CsvTextConverter();
    public final static PlaintextTextConverter CONVERTER_PLAINTEXT = new PlaintextTextConverter();
    public final static AsciidocTextConverter CONVERTER_ASCIIDOC = new AsciidocTextConverter();
    public final static EmbedBinaryTextConverter CONVERTER_EMBEDBINARY = new EmbedBinaryTextConverter();
    public final static OrgmodeTextConverter CONVERTER_ORGMODE = new OrgmodeTextConverter();

    // File extensions that are known not to be supported by Markor
    private static final List<String> EXTERNAL_FILE_EXTENSIONS = Collections.singletonList(".pdf");

    public static class Format {
        public final @StringRes int format, name;
        public final String defaultExtensionWithDot;
        public final TextConverterBase converter;

        public Format(@StringRes final int a_format, @StringRes final int a_name, final String a_defaultFileExtension, final TextConverterBase a_converter) {
            format = a_format;
            name = a_name;
            defaultExtensionWithDot = a_defaultFileExtension;
            converter = a_converter;
        }
    }

    // Order here is used to **determine** format by it's file extension and/or content heading
    public static final List<Format> FORMATS = Arrays.asList(
            new Format(FormatRegistry.FORMAT_MARKDOWN, R.string.markdown, ".md", CONVERTER_MARKDOWN),
            new Format(FormatRegistry.FORMAT_TODOTXT, R.string.todo_txt, ".todo.txt", CONVERTER_TODOTXT),
            new Format(FormatRegistry.FORMAT_CSV, R.string.csv, ".csv", CONVERTER_CSV),
            new Format(FormatRegistry.FORMAT_WIKITEXT, R.string.wikitext, ".txt", CONVERTER_WIKITEXT),
            new Format(FormatRegistry.FORMAT_KEYVALUE, R.string.key_value, ".json", CONVERTER_KEYVALUE),
            new Format(FormatRegistry.FORMAT_ASCIIDOC, R.string.asciidoc, ".adoc", CONVERTER_ASCIIDOC),
            new Format(FormatRegistry.FORMAT_ORGMODE, R.string.orgmode, ".org", CONVERTER_ORGMODE),
            new Format(FormatRegistry.FORMAT_EMBEDBINARY, R.string.embed_binary, ".jpg", CONVERTER_EMBEDBINARY),
            new Format(FormatRegistry.FORMAT_PLAIN, R.string.plaintext, ".txt", CONVERTER_PLAINTEXT),
            new Format(FormatRegistry.FORMAT_UNKNOWN, R.string.none, "", null)
    );

    public static boolean isFileSupported(final File file, final boolean... textOnly) {
        final boolean textonly = textOnly != null && textOnly.length > 0 && textOnly[0];
        if (file != null) {
            for (final Format format : FORMATS) {
                if (textonly && format.converter instanceof EmbedBinaryTextConverter) {
                    continue;
                }
                if (format.converter != null && format.converter.isFileOutOfThisFormat(file)) {
                    return true;
                }
            }
        }
        return false;
    }

    public interface TextFormatApplier {
        void applyTextFormat(int textFormatId);
    }

    public static FormatRegistry getFormat(int formatId, @NonNull final Context context, final Document document) {
        final FormatRegistry format = new FormatRegistry();
        final AppSettings appSettings = AppSettings.get(context);

        switch (formatId) {
            case FORMAT_CSV: {
                format._converter = CONVERTER_CSV;
                format._highlighter = new CsvSyntaxHighlighter(appSettings);

                // TODO k3b ????
                format._textActions = new PlaintextActionButtons(context, document);
                format._autoFormatInputFilter = new AutoTextFormatter(MarkdownReplacePatternGenerator.formatPatterns);
                format._autoFormatTextWatcher = new ListHandler(MarkdownReplacePatternGenerator.formatPatterns);
                break;
            }
            case FORMAT_PLAIN: {
                format._converter = CONVERTER_PLAINTEXT;
                format._highlighter = new PlaintextSyntaxHighlighter(appSettings, document.extension);
                // Should implement code action buttons for PlaintextActionButtons
                format._textActions = new PlaintextActionButtons(context, document);
                format._autoFormatInputFilter = new AutoTextFormatter(MarkdownReplacePatternGenerator.formatPatterns);
                format._autoFormatTextWatcher = new ListHandler(MarkdownReplacePatternGenerator.formatPatterns);
                break;
            }
            case FORMAT_ASCIIDOC: {
                format._converter = CONVERTER_ASCIIDOC;
                format._highlighter = new AsciidocSyntaxHighlighter(appSettings);
                format._textActions = new AsciidocActionButtons(context, document);
                format._autoFormatInputFilter = new AutoTextFormatter(MarkdownReplacePatternGenerator.formatPatterns);
                format._autoFormatTextWatcher = new ListHandler(MarkdownReplacePatternGenerator.formatPatterns);
                break;
            }
            case FORMAT_TODOTXT: {
                format._converter = CONVERTER_TODOTXT;
                format._highlighter = new TodoTxtSyntaxHighlighter(appSettings);
                format._textActions = new TodoTxtActionButtons(context, document);
                format._autoFormatInputFilter = new TodoTxtAutoTextFormatter();
                break;
            }
            case FORMAT_KEYVALUE: {
                format._converter = CONVERTER_KEYVALUE;
                format._highlighter = new KeyValueSyntaxHighlighter(appSettings);
                format._textActions = new PlaintextActionButtons(context, document);
                break;
            }
            case FORMAT_WIKITEXT: {
                format._converter = CONVERTER_WIKITEXT;
                format._highlighter = new WikitextSyntaxHighlighter(appSettings);
                format._textActions = new WikitextActionButtons(context, document);
                format._autoFormatInputFilter = new AutoTextFormatter(WikitextReplacePatternGenerator.formatPatterns);
                format._autoFormatTextWatcher = new ListHandler(WikitextReplacePatternGenerator.formatPatterns);
                break;
            }
            case FORMAT_EMBEDBINARY: {
                format._converter = CONVERTER_EMBEDBINARY;
                format._highlighter = new PlaintextSyntaxHighlighter(appSettings);
                format._textActions = new PlaintextActionButtons(context, document);
                break;
            }
            case FORMAT_ORGMODE: {
                format._converter = CONVERTER_ORGMODE;
                format._highlighter = new OrgmodeSyntaxHighlighter(appSettings);
                format._textActions = new OrgmodeActionButtons(context, document);
                format._autoFormatInputFilter = new AutoTextFormatter(OrgmodeReplacePatternGenerator.formatPatterns);
                format._autoFormatTextWatcher = new ListHandler(OrgmodeReplacePatternGenerator.formatPatterns);
                break;
            }
            default:
            case FORMAT_MARKDOWN: {
                formatId = FORMAT_MARKDOWN;
                format._converter = CONVERTER_MARKDOWN;
                format._highlighter = new MarkdownSyntaxHighlighter(appSettings);
                format._textActions = new MarkdownActionButtons(context, document);
                format._autoFormatInputFilter = new AutoTextFormatter(MarkdownReplacePatternGenerator.formatPatterns);
                format._autoFormatTextWatcher = new ListHandler(MarkdownReplacePatternGenerator.formatPatterns);
                break;
            }
        }
        format._formatId = formatId;
        return format;
    }

    private ActionButtonBase _textActions;
    private SyntaxHighlighterBase _highlighter;
    private TextConverterBase _converter;
    private InputFilter _autoFormatInputFilter;
    private TextWatcher _autoFormatTextWatcher;
    private int _formatId;

    public ActionButtonBase getActions() {
        return _textActions;
    }

    public TextWatcher getAutoFormatTextWatcher() {
        return _autoFormatTextWatcher;
    }

    public InputFilter getAutoFormatInputFilter() {
        return _autoFormatInputFilter;
    }

    public SyntaxHighlighterBase getHighlighter() {
        return _highlighter;
    }

    public TextConverterBase getConverter() {
        return _converter;
    }

    public int getFormatId() {
        return _formatId;
    }

    public static boolean isExternalFile(final File file) {
        final String ext = GsFileUtils.getFilenameExtension(file).toLowerCase();
        return EXTERNAL_FILE_EXTENSIONS.contains(ext);
    }
}
