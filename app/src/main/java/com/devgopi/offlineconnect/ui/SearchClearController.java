package com.devgopi.offlineconnect.ui;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;

/** Dynamically exposes a clear action only while its search field contains text. */
final class SearchClearController {
    private SearchClearController() { }

    static void attach(EditText input, View clearButton) {
        View container = (View) input.getParent();
        input.setOnFocusChangeListener((view, focused) -> container.setSelected(focused));
        clearButton.setOnClickListener(view -> {
            input.getText().clear();
            input.requestFocus();
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count,
                                                     int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before,
                                                 int count) {
                clearButton.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
            }
            @Override public void afterTextChanged(Editable editable) { }
        });
        clearButton.setVisibility(input.length() == 0 ? View.GONE : View.VISIBLE);
    }
}
