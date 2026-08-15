package com.ug.e87idrive;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Immutable test plans shown by the visual USB DEBUG assistant. */
public final class UsbDebugWizard {
    public static final class Step {
        private final String title;
        private final String instruction;
        private final boolean optional;

        Step(String title, String instruction, boolean optional) {
            this.title = title;
            this.instruction = instruction;
            this.optional = optional;
        }

        public String title() { return title; }
        public String instruction() { return instruction; }
        public boolean optional() { return optional; }
    }

    public static final class Plan {
        private final String id;
        private final String title;
        private final String preparation;
        private final List<String> tokens;
        private final List<Step> steps;

        Plan(String id, String title, String preparation, List<String> tokens, Step... steps) {
            this.id = id;
            this.title = title;
            this.preparation = preparation;
            this.tokens = Collections.unmodifiableList(tokens);
            this.steps = Collections.unmodifiableList(Arrays.asList(steps));
        }

        public String id() { return id; }
        public String title() { return title; }
        public String preparation() { return preparation; }
        public List<String> tokens() { return tokens; }
        public List<Step> steps() { return steps; }
    }

    private static final List<Plan> PLANS = Collections.unmodifiableList(Arrays.asList(
            new Plan("lights", "Luces e intermitentes",
                    "Vehículo inmovilizado, motor en un estado seguro y mando de luces en 0. Deja apagadas también "
                            + "antiniebla y emergencia. Pulsa EMPEZAR sin accionar todavía ningún mando.",
                    Arrays.asList("light", "lamp", "illum", "head", "beam", "bright", "night", "far", "luz"),
                    step("Luces de posición", "Enciende únicamente las luces de posición y mantenlas 3 segundos."),
                    step("Luces de cruce", "Pasa a luces de cruce y mantenlas 3 segundos."),
                    step("Luces largas", "Activa las luces largas de forma fija y mantenlas 3 segundos."),
                    step("Intermitente izquierdo", "Apaga las largas y activa el intermitente izquierdo durante al menos 3 destellos."),
                    step("Intermitente derecho", "Cambia al intermitente derecho durante al menos 3 destellos."),
                    optional("Antiniebla", "Si el vehículo lo permite, activa el antiniebla delantero o trasero y mantenlo 3 segundos."),
                    step("Luces de emergencia", "Desactiva el antiniebla y enciende las luces de emergencia durante al menos 3 destellos."),
                    step("Restaurar apagadas", "Apaga emergencia e iluminación y mantén el mando en 0 durante 3 segundos.")),
            new Plan("parking_brake", "Freno de mano",
                    "Coche completamente detenido, terreno llano, pie en el freno y una persona ocupando el puesto "
                            + "del conductor. Deja inicialmente liberado el freno de mano solo si es seguro.",
                    Arrays.asList("park", "parking", "brake", "handbrake", "freno"),
                    step("Activar", "Acciona el freno de mano y mantenlo aplicado 3 segundos."),
                    step("Liberar", "Con el pie en el freno, libera el freno de mano y espera 3 segundos."),
                    step("Repetir activación", "Vuelve a accionar el freno de mano durante 3 segundos."),
                    step("Restaurar", "Libéralo únicamente si es seguro y espera 3 segundos antes de finalizar.")),
            new Plan("doors", "Puertas y portón",
                    "Vehículo detenido y en una ubicación segura. Cierra las cuatro puertas y el portón; espera a "
                            + "que se apaguen los avisos OEM antes de pulsar EMPEZAR.",
                    Arrays.asList("door", "gate", "trunk", "boot", "hood", "open", "puerta", "porton"),
                    step("Abrir conductor", "Abre solo la puerta del conductor y espera 3 segundos."),
                    step("Cerrar conductor", "Cierra la puerta del conductor y espera 3 segundos."),
                    step("Abrir acompañante", "Abre solo la puerta del acompañante y espera 3 segundos."),
                    step("Cerrar acompañante", "Cierra la puerta del acompañante y espera 3 segundos."),
                    step("Abrir trasera izquierda", "Abre solo la puerta trasera izquierda y espera 3 segundos."),
                    step("Cerrar trasera izquierda", "Cierra la puerta trasera izquierda y espera 3 segundos."),
                    step("Abrir trasera derecha", "Abre solo la puerta trasera derecha y espera 3 segundos."),
                    step("Cerrar trasera derecha", "Cierra la puerta trasera derecha y espera 3 segundos."),
                    step("Abrir portón", "Abre solo el portón y espera 3 segundos."),
                    step("Cerrar todo", "Cierra el portón y comprueba que todo el vehículo queda cerrado.")),
            new Plan("seatbelt", "Cinturón del conductor",
                    "Vehículo detenido. Siéntate en el puesto del conductor y deja el cinturón abrochado antes de "
                            + "empezar. No realices esta prueba circulando.",
                    Arrays.asList("belt", "seatbelt", "buckle", "restraint", "cinturon"),
                    step("Desabrochar", "Desabrocha el cinturón y espera 3 segundos."),
                    step("Abrochar", "Abróchalo y espera 3 segundos."),
                    step("Repetir desabrochado", "Desabróchalo una segunda vez y espera 3 segundos."),
                    step("Restaurar abrochado", "Abróchalo de nuevo y espera 3 segundos.")),
            new Plan("outside_temperature", "Temperatura exterior",
                    "Deja visible en la interfaz OEM la temperatura exterior, si existe. Esta magnitud cambia "
                            + "lentamente: el asistente buscará también valores públicos estables, no solo transiciones.",
                    Arrays.asList("temp", "temperature", "outside", "exterior", "ambient"),
                    step("Lectura estable", "No cambies ningún mando. Espera al menos 5 segundos mientras la app registra valores visibles."),
                    step("Comparar con OEM", "Comprueba el valor mostrado por el sistema OEM y marca el paso; anótalo al compartir el TXT.")),
            new Plan("climate_temperature", "Climatizador · temperaturas",
                    "Climatizador encendido y estable. Anota mentalmente las temperaturas izquierda y derecha y no "
                            + "cambies ventilador, AUTO ni A/C durante esta prueba.",
                    Arrays.asList("hvac", "climate", "air", "temp", "left", "right", "driver", "passenger"),
                    step("Subir conductor", "Sube exactamente 1 °C la temperatura del conductor y espera 3 segundos."),
                    step("Bajar conductor", "Bájala exactamente 1 °C para volver al valor inicial y espera 3 segundos."),
                    step("Subir acompañante", "Sube exactamente 1 °C la temperatura del acompañante y espera 3 segundos."),
                    step("Bajar acompañante", "Bájala 1 °C para restaurar el valor inicial y espera 3 segundos.")),
            new Plan("climate_fan", "Climatizador · ventilador",
                    "Climatizador encendido, ventilador en un nivel bajo estable y temperaturas sin tocar. Pulsa "
                            + "EMPEZAR antes de cambiar la intensidad.",
                    Arrays.asList("hvac", "climate", "air", "fan", "blower", "speed", "ventilador"),
                    step("Subir un nivel", "Aumenta un nivel la velocidad del ventilador y espera 3 segundos."),
                    step("Subir otro nivel", "Auméntala un segundo nivel y espera 3 segundos."),
                    step("Bajar un nivel", "Reduce un nivel y espera 3 segundos."),
                    step("Restaurar nivel", "Vuelve al nivel inicial y espera 3 segundos.")),
            new Plan("reverse_pdc", "Marcha atrás y PDC",
                    "PRUEBA CON AYUDANTE: coche inmovilizado, pie en el freno y freno de mano aplicado. Verifica que "
                            + "no haya personas ni obstáculos en la trayectoria. La app no controla cámara ni PDC.",
                    Arrays.asList("reverse", "back", "gear", "park", "pdc", "radar", "distance", "rear", "marcha"),
                    step("Engranar marcha atrás", "Con el pie en el freno y sin mover el coche, engrana R y espera 3 segundos."),
                    optional("Acercar obstáculo manual", "Con R puesta y el coche inmóvil, un ayudante puede acercar lentamente un objeto amplio a un sensor trasero. No muevas el vehículo."),
                    step("Salir de marcha atrás", "Manteniendo el coche inmóvil, vuelve a punto muerto/P y espera 3 segundos."),
                    step("Repetir R", "Engrana R una segunda vez, sin mover el coche, y espera 3 segundos."),
                    step("Finalizar en seguro", "Vuelve a punto muerto/P, mantén el freno de mano y confirma que cámara, PDC y pitidos OEM siguen normales.")),
            new Plan("custom", "Otra señal",
                    "Define antes de empezar un estado A y un estado B que puedas alternar de forma segura con el "
                            + "vehículo detenido. No uses esta opción para conducir mirando la pantalla.",
                    Collections.emptyList(),
                    step("Cambiar a estado B", "Realiza una sola vez el cambio que quieres identificar y espera 3 segundos."),
                    step("Volver a estado A", "Restaura el estado inicial y espera 3 segundos."),
                    step("Repetir estado B", "Repite exactamente el primer cambio y espera 3 segundos."),
                    step("Restaurar estado A", "Restaura de nuevo el estado inicial antes de finalizar."))
    ));

    private UsbDebugWizard() {}

    public static List<Plan> plans() { return PLANS; }

    private static Step step(String title, String instruction) { return new Step(title, instruction, false); }
    private static Step optional(String title, String instruction) { return new Step(title, instruction, true); }
}
