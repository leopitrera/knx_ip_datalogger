import io.calimero.DetachEvent;
import io.calimero.GroupAddress;
import io.calimero.IndividualAddress;
import io.calimero.KNXAddress;
import io.calimero.dptxlator.DPTXlator;
import io.calimero.dptxlator.TranslatorTypes;
import io.calimero.link.KNXNetworkLink;
import io.calimero.link.KNXNetworkLinkIP;
import io.calimero.link.medium.TPSettings;
import io.calimero.process.ProcessCommunicator;
import io.calimero.process.ProcessCommunicatorImpl;
import io.calimero.process.ProcessEvent;
import io.calimero.process.ProcessListener;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.net.InetSocketAddress;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KnxCliLogger implements ProcessListener {

  static class Rule {
    final String gaPattern;   // puede ser "1/0/31" o "1/0/#"
    final GroupAddress gaExact; // si no hay '#', aquí va la GA
    final Integer main;       // si hay '#', main/middle fijados
    final Integer middle;
    final boolean wildcard;

    final String dptId;       // e.g. "9.001"
    final Double min;         // nullable
    final Double max;         // nullable

    Rule(String gaPattern, GroupAddress gaExact, Integer main, Integer middle, boolean wildcard,
         String dptId, Double min, Double max) {
      this.gaPattern = gaPattern;
      this.gaExact = gaExact;
      this.main = main;
      this.middle = middle;
      this.wildcard = wildcard;
      this.dptId = dptId;
      this.min = min;
      this.max = max;
    }

    boolean matchesGA(GroupAddress ga) {
      if (!wildcard) return gaExact.equals(ga);
      return ga.getMainGroup() == main && ga.getMiddleGroup() == middle;
    }

    boolean matchesValue(double v) {
      if (min != null && v < min) return false;
      if (max != null && v > max) return false;
      return true;
    }

    boolean isAll() { return min == null && max == null; }
  }

  private static final Pattern FIRST_NUMBER = Pattern.compile("[-+]?[0-9]*\\.?[0-9]+"); // [web:313]

  private final List<Rule> rules = new ArrayList<>();
  private BufferedWriter out;

  public static void main(final String[] args) throws Exception {
    new KnxCliLogger().runInteractive();
  }

  void runInteractive() throws Exception {
    final Scanner sc = new Scanner(System.in);

    System.out.print("IP interfaz IP-KNX (ej. 192.168.1.55): ");
    final String ip = sc.nextLine().trim();

    System.out.print("Archivo CSV destino (ej. events.csv): ");
    final String outFile = sc.nextLine().trim();

    System.out.println("Introduce grupos a monitorizar, UNA línea por grupo, usando guiones '-':");
    System.out.println("  GA-DPT-all        (guarda todos los valores de ese grupo)");
    System.out.println("  GA-DPT-MIN-MAX    (guarda solo valores en rango)");
    System.out.println("También puedes usar '#': 1/0/#-9.001-all (subgrupo 0..255) [web:383]");
    System.out.println("DPT puede ser numérico (9.001) o alias común:");
    System.out.println("  switch        -> 1.001");
    System.out.println("  percentage    -> 5.001");
    System.out.println("  temperature   -> 9.001");
    System.out.println("  power_kw      -> 9.024   (kW)");
    System.out.println("  power_w       -> 14.056  (W)");
    System.out.println("  humidity      -> 9.007   (%)");
    System.out.println("  illuminance   -> 9.004   (lux)");
    System.out.println("Ejemplos:");
    System.out.println("  1/0/31-temperature-all");
    System.out.println("  1/0/#-temperature-all");
    System.out.println("  1/0/33-9.001-10-40");
    System.out.println("Cuando termines, deja la línea vacía.");

    while (true) {
      System.out.print("> ");
      final String line = sc.nextLine().trim();
      if (line.isEmpty()) break;

      final String[] p = line.split("-", -1);
      if (p.length < 3) {
        System.out.println("Formato inválido. Usa GA-DPT-all o GA-DPT-MIN-MAX");
        continue;
      }

      final String gaText = p[0].trim();
      final String dptId = normalizeDpt(p[1].trim());

      final String third = p[2].trim();
      Double min = null;
      Double max = null;

      if (!third.equalsIgnoreCase("all")) {
        if (p.length < 4) {
          System.out.println("Falta MAX. Usa GA-DPT-MIN-MAX");
          continue;
        }
        min = parseDoubleFlexible(third);
        max = parseDoubleFlexible(p[3].trim());
        if (min == null || max == null) {
          System.out.println("MIN/MAX inválidos.");
          continue;
        }
      }

      // validar DPT
      try { TranslatorTypes.createTranslator(dptId); }
      catch (Exception ex) {
        System.out.println("DPT inválido/no soportado: " + dptId);
        continue;
      }

      Rule r = parseRule(gaText, dptId, min, max);
      if (r == null) continue;

      rules.add(r);
      System.out.println("OK: " + gaText + " DPT=" + dptId + (third.equalsIgnoreCase("all") ? " (all)" : (" rango=" + min + ".." + max)));
    }

    if (rules.isEmpty()) {
      System.out.println("No hay grupos configurados; saliendo.");
      return;
    }

    final boolean newFile = !new File(outFile).exists();
    out = new BufferedWriter(new FileWriter(outFile, true));
    if (newFile) out.write("ts_iso,src,dst,ga,value,payload_hex\n");
    out.flush();

    final InetSocketAddress remote = new InetSocketAddress(ip, 3671);
    try (KNXNetworkLink link = KNXNetworkLinkIP.newTunnelingLink(null, remote, false, new TPSettings());
         ProcessCommunicator pc = new ProcessCommunicatorImpl(link)) {

      pc.addProcessListener(this);

      System.out.println("Monitorizando... Ctrl+C salir.");
      while (link.isOpen()) Thread.sleep(1000);
    }
  }

  private static Rule parseRule(String gaText, String dptId, Double min, Double max) {
    // patrón A/B/#  (3-level, sub 0..255) [web:383]
    if (gaText.endsWith("/#")) {
      String[] parts = gaText.split("/");
      if (parts.length != 3) {
        System.out.println("GA patrón inválida (usa A/B/#): " + gaText);
        return null;
      }
      try {
        int main = Integer.parseInt(parts[0]);
        int middle = Integer.parseInt(parts[1]);
        // Rangos típicos: main 0..31, middle 0..7, sub 0..255 [web:384]
        if (main < 0 || main > 31 || middle < 0 || middle > 7) {
          System.out.println("Fuera de rango (main 0..31, middle 0..7): " + gaText);
          return null;
        }
        return new Rule(gaText, null, main, middle, true, dptId, min, max);
      } catch (Exception ex) {
        System.out.println("GA patrón inválida: " + gaText);
        return null;
      }
    }

    // GA exacta
    try {
      GroupAddress ga = new GroupAddress(gaText);
      return new Rule(gaText, ga, null, null, false, dptId, min, max);
    } catch (Exception ex) {
      System.out.println("GA inválida: " + gaText);
      return null;
    }
  }

  private static String normalizeDpt(String s) {
    s = s.trim().toLowerCase();
    return switch (s) {
      case "temperature", "temp", "t" -> "9.001";
      case "switch", "bool", "binary" -> "1.001";
      case "percentage", "percent", "pct", "%" -> "5.001";
      case "power_kw", "kw", "power-kw" -> "9.024";
      case "power_w", "w", "power-w" -> "14.056";
      case "humidity", "rh" -> "9.007";
      case "illuminance", "lux" -> "9.004";
      default -> s; // DPT manual
    };
  }

  private static Double parseDoubleFlexible(String s) {
    s = s.trim().replace(',', '.');
    try { return Double.parseDouble(s); }
    catch (Exception ex) { return null; }
  }

  private static Double extractFirstNumber(String s) {
    s = s.trim().replace(',', '.');
    Matcher m = FIRST_NUMBER.matcher(s);
    if (!m.find()) return null;
    try { return Double.parseDouble(m.group()); }
    catch (Exception ex) { return null; }
  }

  @Override public void groupWrite(final ProcessEvent e) { handle(e); }
  @Override public void groupReadResponse(final ProcessEvent e) { handle(e); }
  @Override public void groupReadRequest(final ProcessEvent e) { /* opcional */ }
  @Override public void detached(final DetachEvent e) { }

  private void handle(final ProcessEvent e) {
    try {
      byte[] asdu = e.getASDU();

      // DBG SIEMPRE (como querías)
      System.out.println("DBG dst=" + e.getDestination()
          + " asduLen=" + (asdu == null ? -1 : asdu.length)
          + " asdu=" + java.util.HexFormat.of().formatHex(asdu == null ? new byte[0] : asdu));

      if (asdu == null || asdu.length == 0) return;

      KNXAddress dst = e.getDestination();
      if (!(dst instanceof GroupAddress ga)) return;

      Rule matched = null;
      for (Rule r : rules) {
        if (r.matchesGA(ga)) { matched = r; break; }
      }
      if (matched == null) return;

      String ts = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
      IndividualAddress src = e.getSourceAddr();
      String payloadHex = java.util.HexFormat.of().formatHex(asdu);

      // Intentar decodificar según DPT indicado; si falla, guardamos solo hex (como pediste)
      String valueText = "";
      Double valueNum = null;

      try {
        DPTXlator xl = TranslatorTypes.createTranslator(matched.dptId);
        xl.setData(asdu);
        valueText = xl.getValue();
        valueNum = extractFirstNumber(valueText);
      } catch (Exception ignore) {
        // se queda valueText/valueNum vacíos
      }

      // Si hay número, aplicar rango; si no hay número, solo guardamos hex
      if (valueNum != null) {
        if (!matched.matchesValue(valueNum)) return;
      }

      // Imprimir telegrama "bonito" al recibir algo de una GA que te interesa
      if (valueNum != null) {
        System.out.println(ts + " " + src + " -> " + ga + " value=" + valueText + " (payload=" + payloadHex + ")");
      } else {
        System.out.println(ts + " " + src + " -> " + ga + " value=<no-decode> (payload=" + payloadHex + ")");
      }

      out.write(ts + "," + src + "," + dst + "," + ga + "," + (valueNum == null ? "" : valueNum) + "," + payloadHex + "\n");
      out.flush();

    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }
}
