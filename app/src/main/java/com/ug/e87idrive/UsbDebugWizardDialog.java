package com.ug.e87idrive;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Large, touch-friendly visual assistant for passive USB diagnostic captures. */
@SuppressLint("SetTextI18n") // The assistant is a fixed Spanish UI for this specific head unit.
public final class UsbDebugWizardDialog {
    private static final long UI_REFRESH_MS = 750L;
    private static final long USB_FLUSH_MS = 5_000L;
    private static final long MIN_STEP_MS = 3_000L;
    private static final long MAX_SESSION_MS = 10 * 60_000L;

    public interface Host {
        String buildBaseReport();
        void ensureDiagnosticSourcesStarted();
        void stopDiagnosticSourcesIfBackground();
        void onCaptureStateChanged(boolean running, String status);
        void onFinished(String finalCorrelationReport);
        void message(String text);
    }

    private final Activity activity;
    private final DiagnosticEngine diagnostics;
    private final UsbDiagnosticRecorder recorder;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int panel;
    private final int panel2;
    private final int text;
    private final int muted;
    private final int line;
    private final int blue;
    private final int accent;
    private final int green = Color.rgb(72, 196, 118);
    private final int amber = Color.rgb(246, 154, 45);

    private AlertDialog dialog;
    private UsbDebugWizard.Plan plan;
    private int stepIndex = -1;
    private boolean running;
    private boolean finished;
    private long sessionStartedAt;
    private long stepReadyAt;
    private TextView progressLabel;
    private ProgressBar progress;
    private TextView instructionTitle;
    private TextView instruction;
    private TextView liveStatus;
    private LinearLayout candidatesBox;
    private Button repeat;
    private Button skip;
    private Button next;
    private Button stop;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!running) return;
            refreshLiveCandidates();
            handler.postDelayed(this, UI_REFRESH_MS);
        }
    };
    private final Runnable flush = new Runnable() {
        @Override public void run() {
            if (!running) return;
            recorder.updateSession(buildUsbReport("EN CURSO", ""));
            handler.postDelayed(this, USB_FLUSH_MS);
        }
    };
    private final Runnable timeout = () -> finishAndSave("FINALIZADA POR LÍMITE DE 10 MIN", true);

    public UsbDebugWizardDialog(Activity activity, DiagnosticEngine diagnostics,
                                UsbDiagnosticRecorder recorder, Host host,
                                int panel, int panel2, int text, int muted,
                                int line, int blue, int accent) {
        this.activity = activity;
        this.diagnostics = diagnostics;
        this.recorder = recorder;
        this.host = host;
        this.panel = panel;
        this.panel2 = panel2;
        this.text = text;
        this.muted = muted;
        this.line = line;
        this.blue = blue;
        this.accent = accent;
    }

    public void showPlanPicker() {
        List<UsbDebugWizard.Plan> plans = UsbDebugWizard.plans();
        LinearLayout root = vertical();
        root.setPadding(dp(14), dp(8), dp(14), dp(8));
        TextView explanation = label("Elige una prueba. La app indicará cada maniobra y clasificará en directo "
                + "solo las señales que Android permita observar.", 13, text, false);
        explanation.setPadding(dp(5), dp(3), dp(5), dp(9));
        root.addView(explanation);
        LinearLayout planList = vertical();
        for (UsbDebugWizard.Plan candidate : plans) {
            Button planButton = button(candidate.title());
            planButton.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(13));
            planButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            planButton.setPadding(dp(16), 0, dp(12), 0);
            planList.addView(planButton, marginLp(-1, dp(48), 0, 0, 0, dp(5)));
            planButton.setOnClickListener(v -> {
                if (dialog != null) dialog.dismiss();
                showPreparation(candidate);
            });
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(planList);
        root.addView(scroll, lp(-1, 0, 1));
        dialog = new AlertDialog.Builder(activity)
                .setTitle("USB DEBUG · Asistente visual")
                .setView(root)
                .setNegativeButton("CANCELAR", null)
                .create();
        dialog.setOnShowListener(v -> resize(dialog, .76f, .84f));
        dialog.show();
    }

    public boolean isRunning() { return running; }

    public void stopAndSave() {
        if (running) confirmStop();
    }

    public void finishFromHost() {
        if (!running) return;
        finishAndSave("INTERRUMPIDA AL CERRAR LA APP", false);
    }

    private void showPreparation(UsbDebugWizard.Plan selected) {
        plan = selected;
        LinearLayout root = vertical();
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.setBackground(background(panel, panel2, 14, line));

        LinearLayout heading = horizontal();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("USB DEBUG", 18, blue, true);
        TextView readOnly = label("SOLO LECTURA · VEHÍCULO DETENIDO", 11, accent, true);
        readOnly.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        heading.addView(title, lp(0, dp(34), 1));
        heading.addView(readOnly, lp(0, dp(34), 1));
        root.addView(heading);

        progressLabel = label("Preparación · " + plan.title(), 13, text, true);
        progressLabel.setPadding(0, dp(4), 0, dp(5));
        root.addView(progressLabel);
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(plan.steps().size());
        progress.setProgress(0);
        root.addView(progress, lp(-1, dp(8)));

        LinearLayout instructionCard = vertical();
        instructionCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        instructionCard.setBackground(background(Color.rgb(7, 25, 42), Color.rgb(3, 13, 24), 12, blue));
        instructionTitle = label("Antes de empezar", 20, text, true);
        instruction = label(plan.preparation(), 15, text, false);
        instruction.setPadding(0, dp(8), 0, 0);
        instructionCard.addView(instructionTitle);
        instructionCard.addView(instruction);
        root.addView(instructionCard, marginLp(-1, -2, 0, dp(10), 0, dp(9)));

        liveStatus = label("La línea base se tomará cuando pulses EMPEZAR.", 13, muted, true);
        liveStatus.setPadding(dp(12), dp(8), dp(12), dp(8));
        liveStatus.setBackground(background(Color.rgb(5, 17, 29), Color.rgb(3, 10, 19), 9, line));
        root.addView(liveStatus);

        TextView candidateHeading = label("CANDIDATOS OBSERVADOS EN DIRECTO", 12, blue, true);
        candidateHeading.setPadding(dp(2), dp(9), 0, dp(5));
        root.addView(candidateHeading);
        candidatesBox = vertical();
        candidatesBox.addView(emptyCandidate("Todavía no se está capturando."));
        ScrollView candidateScroll = new ScrollView(activity);
        candidateScroll.addView(candidatesBox);
        root.addView(candidateScroll, lp(-1, dp(250)));

        LinearLayout controls = horizontal();
        repeat = button("REPETIR PASO");
        skip = button("OMITIR");
        next = button("EMPEZAR");
        stop = button("CANCELAR");
        repeat.setEnabled(false);
        skip.setEnabled(false);
        controls.addView(repeat, marginLp(0, dp(48), 1, 0, dp(4), 0));
        controls.addView(skip, marginLp(0, dp(48), 1, dp(4), dp(4), 0));
        controls.addView(next, marginLp(0, dp(48), 1.25f, dp(4), dp(4), 0));
        controls.addView(stop, marginLp(0, dp(48), 1, dp(4), 0, 0));
        root.addView(controls);

        dialog = new AlertDialog.Builder(activity).setView(root).create();
        dialog.setCancelable(false);
        dialog.setOnShowListener(v -> resize(dialog, .92f, .92f));
        repeat.setOnClickListener(v -> repeatStep());
        skip.setOnClickListener(v -> completeStep(true));
        next.setOnClickListener(v -> {
            if (finished) dialog.dismiss();
            else if (!running) beginCapture();
            else completeStep(false);
        });
        stop.setOnClickListener(v -> {
            if (running) confirmStop();
            else dialog.dismiss();
        });
        dialog.show();
    }

    private void beginCapture() {
        running = true;
        finished = false;
        sessionStartedAt = System.currentTimeMillis();
        host.ensureDiagnosticSourcesStarted();
        diagnostics.startCorrelation("USB DEBUG · " + plan.title());
        VehicleObservationTrace.guided("ASISTENTE INICIADO", "plan=" + plan.title()
                + " · id=" + plan.id());
        recorder.startSession("wizard_" + plan.id(), buildUsbReport("INICIADA", ""),
                (success, message) -> activity.runOnUiThread(() -> host.message(message)));
        host.onCaptureStateChanged(true, "Asistente activo · " + plan.title());
        repeat.setEnabled(true);
        skip.setEnabled(true);
        stop.setText("DETENER Y GUARDAR");
        stepIndex = 0;
        beginStep();
        handler.post(refresh);
        handler.postDelayed(flush, USB_FLUSH_MS);
        handler.postDelayed(timeout, MAX_SESSION_MS);
    }

    private void beginStep() {
        UsbDebugWizard.Step step = plan.steps().get(stepIndex);
        VehicleObservationTrace.guided("INSTRUCCIÓN MOSTRADA", "plan=" + plan.title()
                + " · paso=" + (stepIndex + 1) + "/" + plan.steps().size()
                + " · acción=" + step.title());
        diagnostics.startCorrelationStep(step.title(), plan.tokens());
        stepReadyAt = System.currentTimeMillis() + MIN_STEP_MS;
        progress.setProgress(stepIndex);
        progressLabel.setText("Paso " + (stepIndex + 1) + " de " + plan.steps().size() + " · " + plan.title());
        instructionTitle.setText(step.title() + (step.optional() ? " · opcional" : ""));
        instruction.setText(step.instruction());
        next.setText(stepIndex == plan.steps().size() - 1 ? "CAPTURAR Y FINALIZAR" : "CAPTURADO · SIGUIENTE");
        candidatesBox.removeAllViews();
        candidatesBox.addView(emptyCandidate("Esperando cambios visibles para la APK…"));
        refreshLiveCandidates();
    }

    private void repeatStep() {
        if (!running || finished) return;
        diagnostics.repeatCorrelationStep();
        VehicleObservationTrace.guided("PASO REPETIDO", "paso=" + (stepIndex + 1)
                + " · acción=" + plan.steps().get(stepIndex).title());
        stepReadyAt = System.currentTimeMillis() + MIN_STEP_MS;
        candidatesBox.removeAllViews();
        candidatesBox.addView(emptyCandidate("Línea base renovada. Repite ahora la maniobra indicada."));
        host.message("Paso reiniciado: realiza de nuevo una sola maniobra");
    }

    private void completeStep(boolean skipped) {
        if (!running || finished) return;
        long remaining = stepReadyAt - System.currentTimeMillis();
        if (!skipped && remaining > 0) {
            host.message("Mantén el estado " + Math.max(1, (remaining + 999) / 1000) + " s más");
            return;
        }
        diagnostics.finishCorrelationStep(skipped);
        VehicleObservationTrace.guided("ACCIÓN DEL USUARIO", "paso=" + (stepIndex + 1)
                + " · " + (skipped ? "omitido" : "confirmado"));
        if (stepIndex >= plan.steps().size() - 1) {
            finishAndSave("FINALIZADA", true);
            return;
        }
        stepIndex++;
        beginStep();
    }

    private void refreshLiveCandidates() {
        if (!running || finished) return;
        long remaining = Math.max(0, stepReadyAt - System.currentTimeMillis());
        List<DiagnosticEngine.LiveCandidate> candidates = diagnostics.liveCandidates();
        int strong = 0;
        int medium = 0;
        for (DiagnosticEngine.LiveCandidate candidate : candidates) {
            if ("FUERTE".equals(candidate.confidence())) strong++;
            else if ("MEDIO".equals(candidate.confidence())) medium++;
        }
        if (remaining > 0) {
            liveStatus.setText("OBSERVANDO · mantén el estado " + ((remaining + 999) / 1000) + " s");
            liveStatus.setTextColor(amber);
            next.setEnabled(false);
        } else if (strong > 0) {
            liveStatus.setText("CAMBIO CAPTURADO · " + strong
                    + (strong == 1 ? " candidato fuerte" : " candidatos fuertes") + " · pendiente de validar");
            liveStatus.setTextColor(green);
            next.setEnabled(true);
        } else if (medium > 0) {
            liveStatus.setText("SEÑAL OBSERVADA · candidato medio; repite el ciclo para reforzarlo");
            liveStatus.setTextColor(amber);
            next.setEnabled(true);
        } else {
            liveStatus.setText("SIN CANDIDATO CLARO · puedes esperar, repetir o continuar para dejar constancia");
            liveStatus.setTextColor(muted);
            next.setEnabled(true);
        }
        showCandidates(candidates);
    }

    private void showCandidates(List<DiagnosticEngine.LiveCandidate> candidates) {
        candidatesBox.removeAllViews();
        if (candidates.isEmpty()) {
            candidatesBox.addView(emptyCandidate("No ha cambiado ninguna señal pública, ajuste legible o broadcast observado."));
            return;
        }
        int limit = Math.min(6, candidates.size());
        for (int i = 0; i < limit; i++) {
            DiagnosticEngine.LiveCandidate candidate = candidates.get(i);
            int tone = "FUERTE".equals(candidate.confidence()) ? green
                    : "MEDIO".equals(candidate.confidence()) ? amber : muted;
            LinearLayout row = vertical();
            row.setPadding(dp(11), dp(7), dp(11), dp(7));
            row.setBackground(background(Color.rgb(7, 22, 36), Color.rgb(3, 13, 23), 9, tone));
            TextView heading = label("● " + candidate.confidence() + " " + candidate.score()
                    + " · " + compact(candidate.key(), 78), 12, tone, true);
            String before = candidate.baseline() == null ? "(sin línea base)" : candidate.baseline();
            TextView values = label(compact(before, 35) + "  →  " + compact(candidate.current(), 42)
                    + "   · " + candidate.changes() + " cambios · fuente "
                    + compact(candidate.source(), 38), 11, text, false);
            TextView reason = label(candidate.reason(), 10, muted, false);
            row.addView(heading);
            row.addView(values);
            row.addView(reason);
            candidatesBox.addView(row, marginLp(-1, -2, 0, 0, 0, dp(5)));
        }
    }

    private void confirmStop() {
        new AlertDialog.Builder(activity)
                .setTitle("¿Detener y guardar?")
                .setMessage("Se guardarán los pasos realizados y los candidatos observados hasta este momento.")
                .setPositiveButton("DETENER Y GUARDAR", (d, w) -> finishAndSave("DETENIDA POR EL USUARIO", true))
                .setNegativeButton("CONTINUAR", null)
                .show();
    }

    private void finishAndSave(String stage, boolean updateUi) {
        if (!running) return;
        handler.removeCallbacks(refresh);
        handler.removeCallbacks(flush);
        handler.removeCallbacks(timeout);
        if (diagnostics.currentCorrelationStepStartedAt() != 0) diagnostics.finishCorrelationStep(true, true);
        String correlationReport = diagnostics.stopCorrelation();
        VehicleObservationTrace.guided("ASISTENTE CERRADO", "estado=" + stage
                + " · pasos_realizados=" + (stepIndex + 1));
        running = false;
        finished = true;
        String finalReport = buildUsbReport(stage, correlationReport);
        recorder.finishSession(finalReport,
                (success, message) -> activity.runOnUiThread(() -> host.message(message)));
        host.onCaptureStateChanged(false, recorder.directorySummary());
        host.onFinished(correlationReport);
        host.stopDiagnosticSourcesIfBackground();
        if (!updateUi || dialog == null) return;
        progress.setProgress(plan.steps().size());
        progressLabel.setText("Prueba terminada · " + plan.title());
        instructionTitle.setText("Captura guardada");
        instruction.setText("Los candidatos medios y fuertes se han añadido al historial interno; el informe también "
                + "se ha escrito en la USB y en la copia de recuperación. Puedes revisarlos en USB DEBUG > Ver "
                + "candidatos guardados. Ninguno se activa automáticamente como mapeo.");
        liveStatus.setText("FINALIZADA · ya puedes cerrar o realizar otra prueba desde USB DEBUG");
        liveStatus.setTextColor(green);
        repeat.setEnabled(false);
        skip.setEnabled(false);
        next.setEnabled(true);
        next.setText("CERRAR");
        stop.setEnabled(false);
    }

    private String buildUsbReport(String stage, String correlationReport) {
        StringBuilder out = new StringBuilder(40_000);
        out.append("IDRIVE USB DEBUG · ASISTENTE VISUAL · JCRK01/CYA · SOLO LECTURA\n");
        out.append("Estado: ").append(stage).append('\n');
        out.append("Plan: ").append(plan == null ? "snapshot general" : plan.title()).append('\n');
        if (stepIndex >= 0 && plan != null && stepIndex < plan.steps().size()) {
            out.append("Paso visible: ").append(stepIndex + 1).append('/').append(plan.steps().size())
                    .append(" · ").append(plan.steps().get(stepIndex).title()).append('\n');
        }
        if (sessionStartedAt != 0) out.append("Duración ms: ")
                .append(System.currentTimeMillis() - sessionStartedAt).append('\n');
        out.append("Importante: los resultados son observaciones Android clasificadas, no tramas CAN/UART ni códigos "
                + "propietarios confirmados.\n\n");
        out.append(host.buildBaseReport());
        if (correlationReport != null && !correlationReport.isEmpty()) out.append("\n\n").append(correlationReport);
        return out.toString();
    }

    private TextView emptyCandidate(String value) {
        TextView view = label(value, 12, muted, false);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(18), dp(12), dp(18));
        return view;
    }

    private Button button(String value) {
        Button button = new Button(activity);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(11));
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(3), 0, dp(3), 0);
        button.setBackground(background(Color.rgb(9, 27, 44), Color.rgb(5, 17, 29), 9, line));
        return button;
    }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private GradientDrawable background(int start, int end, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{start, end});
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private LinearLayout.LayoutParams lp(int width, int height, float weight) {
        return new LinearLayout.LayoutParams(width, height, weight);
    }

    private LinearLayout.LayoutParams marginLp(int width, int height, float weight,
                                                int left, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height, weight);
        params.setMargins(left, 0, right, bottom);
        return params;
    }

    private void resize(AlertDialog target, float width, float height) {
        Window window = target.getWindow();
        if (window == null) return;
        window.setLayout((int) (activity.getResources().getDisplayMetrics().widthPixels * width),
                (int) (activity.getResources().getDisplayMetrics().heightPixels * height));
    }

    private float scale() {
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        return Math.min(metrics.widthPixels / 1280f, metrics.heightPixels / 720f);
    }

    private float px(float value) { return value * scale(); }
    private int dp(int value) { return (int) (px(value) + .5f); }

    private static String compact(String value, int max) {
        if (value == null) return "(null)";
        String oneLine = value.replace('\n', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "…";
    }
}
