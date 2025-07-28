// ResultsActivity.java
package com.example.eyegod;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class ResultsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_results);
        TextView textViewResults = findViewById(R.id.textViewResults);
        String results = getIntent().getStringExtra("results");
        textViewResults.setText(results);
    }
}