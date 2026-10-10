package com.diegonmarcos.cloudcode.chat;

import android.content.Context;

import com.diegonmarcos.superapp.modelcatalogue.CatalogueJson;
import com.diegonmarcos.superapp.modelcatalogue.FileCatalogueStore;
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogue;
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogueRepository;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The model picker's data: libs:model-catalogue, the SAME selection, pricing, order and cell text
 * Cloud Search's catalogue page draws (the lib's logic package and its assets are compiled into
 * this app by reference, see build-extras.gradle), narrowed to the sections the web page asks for
 * (nav.json::chat.catalogue.sections, A0 Code) and handed over as CatalogueJson. Nothing is parsed,
 * priced or formatted here.
 */
final class CatalogueBridge {
    private final Context app;
    private final Http http;

    interface Http {
        /** The body of a 2xx GET, else null. No auth: the models API needs no key. */
        String get(String url);
    }

    CatalogueBridge(Context context, Http http) {
        this.app = context.getApplicationContext();
        this.http = http;
    }

    /** [refresh] false = what is stored (cache or snapshot), at once; true = load (a refresh when a day old). */
    JSONObject shown(JSONArray sectionIds, boolean refresh) throws Exception {
        ModelCatalogue.Catalogue all = ModelCatalogue.INSTANCE.parse(asset(ModelCatalogue.ASSET));
        Set<String> ids = null;
        if (sectionIds != null && sectionIds.length() > 0) {
            ids = new LinkedHashSet<>();
            for (int i = 0; i < sectionIds.length(); i++) ids.add(sectionIds.getString(i));
        }
        ModelCatalogue.Catalogue cat = all.only(ids);
        // Priced over the narrowed catalogue: only /models and the shown rows' endpoints are asked.
        ModelCatalogueRepository repo = new ModelCatalogueRepository(
            cat, http::get, new FileCatalogueStore(new File(app.getFilesDir(), "model-catalogue")), System::currentTimeMillis);
        ModelCatalogueRepository.Priced p = refresh ? repo.load() : repo.stored();
        return CatalogueJson.INSTANCE.shown(cat, p, repo.asOf(p));
    }

    private String asset(String path) throws Exception {
        try (InputStream in = app.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
