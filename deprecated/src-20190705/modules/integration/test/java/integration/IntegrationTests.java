package integration;

import de.sormuras.bach.Project;
import java.nio.file.Path;

public class IntegrationTests {
  public static void main(String[] args) {
    System.out.println(Project.class + " is in " + Project.class.getModule());
    var bach = Project.of(Path.of("bach.properties"));
    System.out.println(bach.toString());
    bach.toStrings(System.out::println);
  }
}
