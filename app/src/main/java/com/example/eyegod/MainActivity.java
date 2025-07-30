package com.example.eyegod;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import androidx.core.content.FileProvider;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import android.util.Log;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private EditText editTextQuery;
    private Button buttonSearch, buttonAddFile, buttonShowFiles;
    private Button buttonNextPage;
    private TextView textViewResults, textViewVersion, textViewSearchType;
    private ListView listViewFiles;
    private LinearLayout layoutFileActions;
    private Button buttonDeleteFile, buttonRenameFile, buttonShareFile;
    private static final int REQUEST_CODE_PERMISSION = 100;
    private File csvDir;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Uri lastPickedUri;
    private File selectedFile = null;

    // Для поиска
    private Thread searchThread = null;
    private volatile boolean shouldStopSearch = false;
    private List<String> allResults = new ArrayList<>();
    private int currentPage = 0;
    private static final int RESULTS_PER_PAGE = 50;

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        lastPickedUri = uri;
                        importAndNormalizeCSV(uri);
                    }
                }
            }
    );
    private static class SearchResult {
        File csvFile;
        int lineNumber;
    }
    private List<SearchResult> searchResults = new ArrayList<>();
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Инициализация всех View
        editTextQuery = findViewById(R.id.editTextQuery);
        buttonSearch = findViewById(R.id.buttonSearch);
        buttonAddFile = findViewById(R.id.buttonAddFile);
        buttonNextPage = findViewById(R.id.buttonNextPage);
        textViewSearchType = findViewById(R.id.textViewSearchType);
        textViewResults = findViewById(R.id.textViewResults);
        textViewVersion = findViewById(R.id.textViewVersion);
        buttonShowFiles = findViewById(R.id.buttonShowFiles);
        listViewFiles = findViewById(R.id.listViewFiles);
        layoutFileActions = findViewById(R.id.layoutFileActions);
        buttonDeleteFile = findViewById(R.id.buttonDeleteFile);
        buttonRenameFile = findViewById(R.id.buttonRenameFile);
        buttonShareFile = findViewById(R.id.buttonShareFile);

        // Настройка директории
        csvDir = new File(getExternalFilesDir(null), "csv");
        if (!csvDir.exists()) csvDir.mkdirs();

        // Разрешения
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_CODE_PERMISSION);
        }

        // Настройка всех компонентов
        setVersionName();
        setupSearchTypeDetector();
        setupSearchButton();
        setupNextPageButton();
        setupFileManagementButtons();
        buttonAddFile.setOnClickListener(v -> pickFile());
        copySamplesFromAssets();
    }
    private void setVersionName() {
        try {
            String versionName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            textViewVersion.setText("Версия: v" + versionName);
        } catch (Exception e) {
            textViewVersion.setText("Версия: v1.0.2");
        }
    }
    private void setupSearchTypeDetector() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable[] searchRunnable = new Runnable[1];

        editTextQuery.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                String query = s.toString().trim();

                if (searchRunnable[0] != null) {
                    handler.removeCallbacks(searchRunnable[0]);
                }

                if (query.isEmpty()) {
                    textViewSearchType.setText("Тип запроса не определён");
                    textViewSearchType.setVisibility(View.GONE);
                    return;
                }

                searchRunnable[0] = () -> {
                    String queryType = detectQueryType(query);
                    String displayText = getDisplayTextForType(queryType);
                    textViewSearchType.setText(displayText);
                    textViewSearchType.setVisibility(View.VISIBLE);
                };

                handler.postDelayed(searchRunnable[0], 3000);
            }
        });
    }
    private void setupSearchButton() {
        buttonSearch.setOnClickListener(v -> {
            if (searchThread == null || !searchThread.isAlive()) {
                startSearch();
                buttonSearch.setText("СТОП");
            } else {
                shouldStopSearch = true;
                buttonSearch.setText("Поиск");
                Toast.makeText(this, "Поиск остановлен", Toast.LENGTH_SHORT).show();
            }
        });
    }
    private void showCurrentPage() {
        int fromIndex = currentPage * RESULTS_PER_PAGE;
        int toIndex = Math.min(fromIndex + RESULTS_PER_PAGE, allResults.size());

        if (fromIndex < 0 || fromIndex >= allResults.size()) {
            runOnUiThread(() -> {
                textViewResults.setText("Ошибка: неверная страница.");
                buttonNextPage.setVisibility(View.GONE);
            });
            return;
        }

        List<String> pageResults = allResults.subList(fromIndex, toIndex);
        String text = String.join("", pageResults);

        runOnUiThread(() -> {
            textViewResults.setText(text);

            // ✅ Проверяем, есть ли следующая страница
            if (toIndex < allResults.size()) {
                // Есть ещё результаты — показываем кнопку
                buttonNextPage.setText("Следующие 50 результатов");
                buttonNextPage.setEnabled(true);
                buttonNextPage.setVisibility(View.VISIBLE);
            } else {
                // Больше нет результатов
                buttonNextPage.setText("Больше нет результатов");
                buttonNextPage.setEnabled(false);
                buttonNextPage.setVisibility(View.VISIBLE);
            }
        });
    }
    private void setupNextPageButton() {
        buttonNextPage.setOnClickListener(v -> {
            currentPage++;
            showCurrentPage();
        });
    }

    private void setupFileManagementButtons() {
        buttonShowFiles.setOnClickListener(v -> showFilesList());

        buttonDeleteFile.setOnClickListener(v -> {
            if (selectedFile == null) return;
            new AlertDialog.Builder(this)
                    .setTitle("Удалить файл")
                    .setMessage("Удалить файл:\n" + selectedFile.getName() + "?")
                    .setPositiveButton("Да", (d, w) -> {
                        // Удаляем основной CSV-файл
                        boolean csvDeleted = selectedFile.delete();

                        // Формируем путь к файлу индекса и удаляем его
                        File indexFile = new File(selectedFile.getParent(), selectedFile.getName() + ".idx");
                        boolean idxDeleted = true;
                        if (indexFile.exists()) {
                            idxDeleted = indexFile.delete();
                        }

                        // Проверяем, успешно ли удалён основной файл
                        if (csvDeleted) {
                            Toast.makeText(this, "Файл удалён", Toast.LENGTH_SHORT).show();
                            selectedFile = null;
                            layoutFileActions.setVisibility(View.GONE);
                            showFilesList();
                        } else {
                            Toast.makeText(this, "Ошибка при удалении", Toast.LENGTH_SHORT).show();
                        }

                        // Опционально: можно проигнорировать ошибку удаления .idx или показать предупреждение
                        // if (!idxDeleted) {
                            // Log.w("MainActivity", "Не удалось удалить файл индекса: " + indexFile.getName());
                        // }
                    })
                    .setNegativeButton("Нет", null)
                    .show();
        });

        buttonRenameFile.setOnClickListener(v -> {
            if (selectedFile == null) return;
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Переименовать");
            final EditText input = new EditText(this);
            input.setText(selectedFile.getName());
            builder.setView(input);
            builder.setPositiveButton("OK", (d, w) -> {
                String newName = input.getText().toString().trim();
                if (newName.isEmpty() || !newName.toLowerCase().endsWith(".csv")) {
                    Toast.makeText(this, "Введите имя с .csv", Toast.LENGTH_SHORT).show();
                    return;
                }
                File newFile = new File(csvDir, newName);
                if (newFile.exists()) {
                    Toast.makeText(this, "Файл уже существует", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Переименовываем основной CSV-файл
                boolean csvRenamed = selectedFile.renameTo(newFile);

                // Если CSV успешно переименован, переименовываем и .idx файл
                if (csvRenamed) {
                    File oldIndexFile = new File(selectedFile.getParent(), selectedFile.getName() + ".idx");
                    File newIndexFile = new File(csvDir, newName + ".idx");

                    boolean idxRenamed = true;
                    if (oldIndexFile.exists()) {
                        idxRenamed = oldIndexFile.renameTo(newIndexFile);
                    }

                    // Сообщаем о результате
                    if (idxRenamed) {
                        Toast.makeText(this, "Переименован", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "CSV переименован, но не удалось переименовать индекс", Toast.LENGTH_SHORT).show();
                    }

                    // Обновляем ссылку на выбранный файл
                    selectedFile = newFile;
                    showFilesList();
                } else {
                    Toast.makeText(this, "Ошибка при переименовании", Toast.LENGTH_SHORT).show();
                }
            });
            builder.setNegativeButton("Отмена", null);
            builder.show();
        });

        buttonShareFile.setOnClickListener(v -> {
            if (selectedFile == null) return;
            Uri fileUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", selectedFile);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/csv");
            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Отправить"));
        });
    }

    private String detectQueryType(String query) {
        query = query.trim();
        if (query.isEmpty()) return "all";

        if (query.matches("\\d+")) {
            return "tel";
        }
        if (query.contains("@")) {
            return "email";
        }
        if (query.startsWith("@") || (query.toLowerCase().startsWith("id") && query.substring(2).matches("\\d+"))) {
            return "tg_id";
        }
        if (query.matches("[a-zA-Zа-яА-ЯёЁ]+")) {
            return "name";
        }
        return "all";
    }

    private String getDisplayTextForType(String type) {
        switch (type) {
            case "tel": return "🔍 Поиск по: Телефон";
            case "email": return "📧 Поиск по: Email";
            case "tg_id": return "💬 Поиск по: Telegram ID";
            case "name": return "👤 Поиск по: Имя";
            case "all": default: return "🔎 Поиск по: всем полям";
        }
    }

    private void startSearch() {
        String query = editTextQuery.getText().toString().trim();
        String queryLower = query.toLowerCase(); // Объявлена вне потока

        if (query.isEmpty()) {
            runOnUiThread(() -> {
                textViewResults.setText("Введите запрос.");
                buttonNextPage.setVisibility(View.GONE);
                buttonSearch.setText("Поиск");
            });
            return;
        }

        // Останавливаем предыдущий поиск, если он был
        if (searchThread != null && searchThread.isAlive()) {
            shouldStopSearch = true;
            try {
                searchThread.join(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // ✅ ОЧИЩАЕМ allResults ПЕРЕД НОВЫМ ПОИСКОМ
        allResults.clear(); // ✅ Добавляем эту строку
        // List<String> tempResults = new ArrayList<>();
        shouldStopSearch = false;

        searchThread = new Thread(() -> {
            String queryType = detectQueryType(query);

            File[] files = csvDir.listFiles((dir, name) -> name.endsWith(".csv"));
            if (files == null || files.length == 0) {
                runOnUiThread(() -> {
                    textViewResults.setText("Нет файлов для поиска.");
                    buttonSearch.setText("Поиск");
                });
                return;
            }

            for (File file : files) {
                if (shouldStopSearch) break;

                final String fileName = file.getName();
                runOnUiThread(() -> {
                    textViewResults.setText("🔍 Поиск в файле: " + fileName + "...");
                });

                File indexFile = new File(file.getParent(), file.getName() + ".idx");
                if (!indexFile.exists()) continue;

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(indexFile)))) {
                    String line;
                    int lineNumberInIdx = 0; // Номер строки в .idx файле

                    while (!shouldStopSearch && (line = reader.readLine()) != null) {
                        lineNumberInIdx++;

                        boolean found = false;
                        switch (queryType) {
                            case "tel":
                            case "email":
                            case "tg_id":
                            case "name":
                            default:
                                if (line.contains(queryLower)) {
                                    found = true;
                                }
                                break;
                        }

                        if (found) {
                            // ✅ В .idx нет заголовка, в CSV — есть
                            // Поэтому номер строки в CSV = lineNumberInIdx + 1
                            String originalData = readLineFromCsv(file, lineNumberInIdx);
                            if (originalData != null) {
                                allResults.add(originalData);
                            }
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    Log.e("Search", "Ошибка чтения индекса: " + indexFile.getName(), e);
                }
            }

            // Показываем результаты
            runOnUiThread(() -> {
                if (shouldStopSearch) {
                    Toast.makeText(this, "Поиск остановлен", Toast.LENGTH_SHORT).show();
                } else {
                    if (allResults.isEmpty()) {
                        textViewResults.setText("Ничего не найдено: " + query);
                    } else {
                        currentPage = 0;
                        showCurrentPage();
                        buttonNextPage.setVisibility(allResults.size() > RESULTS_PER_PAGE ? View.VISIBLE : View.GONE);
                    }
                }
                buttonSearch.setText("Поиск");
            });
        });

        searchThread.start();
    }
    private String readLineFromCsv(File csvFile, int targetLine) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(csvFile)))) {
            String line;
            int currentLine = 0;
            while ((line = reader.readLine()) != null) {
                if (currentLine == targetLine) {
                    // Убедитесь, что parts.length соответствует headers.length
                    String[] parts = line.split("[;|]", -1);
                    String headerLine = getHeaderLine(csvFile);
                    if (headerLine == null) return null;

                    String[] headers = headerLine.trim().split("[;|]", -1);
                    StringBuilder result = new StringBuilder();
                    result.append("База: ").append(csvFile.getName()).append("\n");
                    for (int i = 0; i < headers.length; i++) {
                        String value = i < parts.length ? cleanField(parts[i]) : "";
                        result.append(headers[i]).append(": ").append(value.isEmpty() ? "отсутствует" : value).append("\n");
                    }
                    result.append("\n");
                    return result.toString();
                }
                currentLine++;
            }
        } catch (IOException e) {
            e.printStackTrace();
            Log.e("Search", "Ошибка чтения CSV: " + csvFile.getName(), e);
        }
        return null;
    }
    private String getOriginalLineFromFile(File csvFile, int lineNumber) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(csvFile)))) {
            String line;
            int currentLine = 0;
            while ((line = reader.readLine()) != null) {
                if (currentLine == lineNumber) {
                    String[] parts = line.split("[;|]", -1);
                    String headerLine = getHeaderLine(csvFile);
                    if (headerLine == null) return null;

                    String[] headers = headerLine.trim().split("[;|]", -1);
                    StringBuilder result = new StringBuilder();
                    result.append("База: ").append(csvFile.getName()).append("\n");
                    for (int i = 0; i < headers.length; i++) {
                        String value = i < parts.length ? cleanField(parts[i]) : "";
                        result.append(headers[i]).append(": ").append(value.isEmpty() ? "отсутствует" : value).append("\n");
                    }
                    result.append("\n");
                    return result.toString();
                }
                currentLine++;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private String getHeaderLine(File csvFile) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(csvFile)))) {
            return reader.readLine();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }


    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        String[] mimeTypes = {"text/plain", "text/csv", "application/csv", "application/vnd.ms-excel", "*/*"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        filePickerLauncher.launch(intent);
    }
    private void createSearchIndex(File csvFile, String[] headers) {
        File indexFile = new File(csvFile.getParent(), csvFile.getName() + ".idx");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(csvFile)));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(indexFile)))) {

            String headerLine = reader.readLine(); // skip
            if (headerLine == null) return;

            // Определяем индексы нужных полей
            int telIndex = -1, nameIndex = -1, emailIndex = -1, tgIdIndex = -1;
            for (int i = 0; i < headers.length; i++) {
                String h = headers[i].toLowerCase();
                if (h.contains("tel") || h.contains("phone")) telIndex = i;
                else if (h.contains("name") || h.contains("фио")) nameIndex = i;
                else if (h.contains("mail") || h.contains("email")) emailIndex = i;
                else if (h.contains("tg") || h.contains("telegram")) tgIdIndex = i;
            }

            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("[;|]", -1);
                if (parts.length < headers.length) continue;

                StringBuilder searchableLine = new StringBuilder();

                // Добавляем только нужные поля
                if (telIndex != -1 && telIndex < parts.length) {
                    searchableLine.append(cleanField(parts[telIndex]).toLowerCase()).append(" ");
                }
                if (nameIndex != -1 && nameIndex < parts.length) {
                    searchableLine.append(cleanField(parts[nameIndex]).toLowerCase()).append(" ");
                }
                if (emailIndex != -1 && emailIndex < parts.length) {
                    searchableLine.append(cleanField(parts[emailIndex]).toLowerCase()).append(" ");
                }
                if (tgIdIndex != -1 && tgIdIndex < parts.length) {
                    searchableLine.append(cleanField(parts[tgIdIndex]).toLowerCase()).append(" ");
                }

                String indexLine = searchableLine.toString().trim();
                if (!indexLine.isEmpty()) {
                    writer.write(indexLine);
                    writer.newLine();
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    private void importAndNormalizeCSV(Uri uri) {
        new Thread(() -> {
            try {
                InputStream inputStream = getContentResolver().openInputStream(uri);
                if (inputStream == null) {
                    mainHandler.post(() ->
                            Toast.makeText(this, "Не удалось открыть файл", Toast.LENGTH_LONG).show()
                    );
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                String headerLine = reader.readLine();
                if (headerLine == null) {
                    mainHandler.post(() ->
                            Toast.makeText(this, "Файл пустой", Toast.LENGTH_LONG).show()
                    );
                    return;
                }

                String delimiter = headerLine.contains(";") ? ";" : "\\|";
                String[] headers = headerLine.trim().split(delimiter, -1);

                inputStream.close();
                inputStream = getContentResolver().openInputStream(uri);
                reader = new BufferedReader(new InputStreamReader(inputStream));
                reader.readLine(); // skip header

                Map<String, Integer> fieldMapping = showMappingDialog(headers);
                if (fieldMapping == null) return;

                String fileName = getFileName(uri);
                if (fileName == null || fileName.isEmpty()) {
                    fileName = "imported_" + System.currentTimeMillis() + ".csv";
                }

                File outputFile = getUniqueFileForSaveByHash(uri, fileName);
                if (outputFile == null) {
                    final String finalFileName = fileName;
                    mainHandler.post(() ->
                            Toast.makeText(this, "Файл уже добавлен: " + finalFileName, Toast.LENGTH_SHORT).show()
                    );
                    return;
                }

                boolean success = saveFileFromUri(uri, outputFile);
                if (success) {
                    createSearchIndex(outputFile, headers);
                    saveSearchTemplate(outputFile.getName(), headers);
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Файл добавлен: " + outputFile.getName(), Toast.LENGTH_LONG).show();
                        showFilesList();
                    });
                } else {
                    mainHandler.post(() ->
                            Toast.makeText(this, "Ошибка при сохранении", Toast.LENGTH_SHORT).show()
                    );
                }

            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() ->
                        Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        }).start();
    }

    private Map<String, Integer> showMappingDialog(String[] headers) {
        Map<String, Integer> mapping = new HashMap<>();
        boolean[] finished = {false};

        mainHandler.post(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Сопоставьте поля");

            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(32, 16, 32, 16);

            List<EditText> editTexts = new ArrayList<>();
            for (String header : headers) {
                TextView tv = new TextView(this);
                tv.setText("Поле: " + header);
                layout.addView(tv);

                EditText et = new EditText(this);
                et.setHint("Ключ (tel, email, name, tg_id или новый)");
                et.setText(inferKey(header));
                layout.addView(et);
                editTexts.add(et);
            }

            ScrollView scrollView = new ScrollView(this);
            scrollView.addView(layout);
            builder.setView(scrollView);

            builder.setPositiveButton("OK", (d, w) -> {
                for (int i = 0; i < headers.length; i++) {
                    String key = editTexts.get(i).getText().toString().trim().toLowerCase();
                    if (!key.isEmpty()) {
                        mapping.put(key, i);
                    }
                }
                finished[0] = true;
            });
            builder.setNegativeButton("Отмена", (d, w) -> {
                mapping.clear();
                finished[0] = true;
            });

            AlertDialog dialog = builder.create();
            dialog.setCancelable(false);
            dialog.show();
        });

        while (!finished[0]) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return null;
            }
        }

        return mapping.isEmpty() ? null : mapping;
    }

    private String inferKey(String header) {
        header = header.toLowerCase();
        if (header.contains("tel") || header.contains("phone")) return "tel";
        if (header.contains("mail") || header.contains("email")) return "email";
        if (header.contains("name") || header.contains("fio") || header.contains("фио")) return "name";
        if (header.contains("tg") || header.contains("telegram")) return "tg_id";
        return header;
    }

    private void saveSearchTemplate(String fileName, String[] headers) {
        SharedPreferences prefs = getSharedPreferences("templates", MODE_PRIVATE);
        StringBuilder sb = new StringBuilder();
        for (String header : headers) {
            sb.append("$").append(header.trim().toLowerCase()).append(" ");
        }
        prefs.edit().putString(fileName, sb.toString().trim()).apply();
    }

    private String getFileName(Uri uri) {
        String result = null;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1) {
                    result = cursor.getString(nameIndex);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (result == null) {
            result = new File(uri.getPath()).getName();
        }
        return result;
    }

    private File getUniqueFileForSaveByHash(Uri uri, String originalFileName) throws IOException {
        if (!originalFileName.toLowerCase().endsWith(".csv")) {
            originalFileName += ".csv";
        }

        File targetFile = new File(csvDir, originalFileName);
        String baseName = originalFileName.substring(0, originalFileName.length() - 4);
        int counter = 1;

        String newFileHash = calculateFileHash(uri);
        if (newFileHash == null) return targetFile;

        while (targetFile.exists()) {
            String existingHash = calculateFileHashForFile(targetFile);
            if (newFileHash.equals(existingHash)) {
                return null;
            }
            targetFile = new File(csvDir, baseName + "_" + counter + ".csv");
            counter++;
        }

        return targetFile;
    }

    private String calculateFileHash(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            return calculateMD5(is);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private String calculateFileHashForFile(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            return calculateMD5(fis);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private String calculateMD5(InputStream is) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private boolean saveFileFromUri(Uri uri, File outputFile) {
        try (InputStream is = getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(outputFile)) {
            if (is == null) return false;
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                os.write(buffer, 0, read);
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private String cleanField(String s) {
        return s == null ? "" : s.trim().replaceAll("^\"|\"$", "");
    }

    private void showFilesList() {
        File[] files = csvDir.listFiles((dir, name) -> name.endsWith(".csv"));
        if (files == null || files.length == 0) {
            Toast.makeText(this, "Нет файлов", Toast.LENGTH_SHORT).show();
            listViewFiles.setAdapter(null);
            listViewFiles.setVisibility(View.GONE);
            layoutFileActions.setVisibility(View.GONE);
            selectedFile = null;
            return;
        }

        List<String> fileNames = new ArrayList<>();
        for (File f : files) fileNames.add(f.getName());

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, fileNames);
        listViewFiles.setAdapter(adapter);
        listViewFiles.setVisibility(View.VISIBLE);
        layoutFileActions.setVisibility(View.GONE); // Скрываем панель при открытии
        selectedFile = null;

        // ✅ Ключевая строка: при клике на файл - показываем кнопки
        listViewFiles.setOnItemClickListener((parent, view, position, id) -> {
            selectedFile = files[position];
            layoutFileActions.setVisibility(View.VISIBLE);
        });
    }

    private void copySamplesFromAssets() {
        // Опционально
    }
}