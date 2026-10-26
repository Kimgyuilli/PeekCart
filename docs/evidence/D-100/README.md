# D-100 Flyway 근거 (2026-10-21)

## 해석 버전과 기록 경계

- 새로 실행한 작업 트리 HEAD: `bf65632` (기준 코드 `be535c57ced11acfcbe9a24a349ddb6ad2055d8a`; D-100 문서만 추가된 상태).
- 명령: `./gradlew :payment-service:dependencyInsight --dependency org.flywaydb:flyway-core --configuration runtimeClasspath --offline --no-daemon --console=plain`
- 2026-10-21 KST 실행, 종료 코드 0. 아래는 줄 끝 공백만 제거한 전체 stdout/stderr다. 로컬 캐시를 사용한 해석이며 DB를 연결하지 않는다.
- 과거 `42030ff`에 대한 기존 `historical-dependency.log`도 `11.7.2 (selected by rule)`을 기록했다. D-100에서 과거 checkout을 새로 실행한 결과와 혼동하지 않도록 기존 로그를 별도로 보존했다.
- `git diff 42030ff be535c57 -- '*.gradle' '**/*.gradle' gradle.properties gradle`를 직접 확인했다. 의존 선언·플러그인 버전·BOM import·wrapper 설정은 그대로이며, 변경은 주석과 가드의 설명/오류 문자열이다. 두 시점 모두 Boot `3.5.12` BOM을 import하고 payment는 버전 없이 `org.flywaydb:flyway-core` / `flyway-mysql`을 선언한다.

```text
To honour the JVM settings for this build a single-use Daemon process will be forked. For more on this, please refer to https://docs.gradle.org/8.14.4/userguide/gradle_daemon.html#sec:disabling_the_daemon in the Gradle documentation.
Daemon will be stopped at the end of the build

> Task :payment-service:dependencyInsight
org.flywaydb:flyway-core:11.7.2 (selected by rule)
  Variant runtime:
    | Attribute Name                 | Provided     | Requested    |
    |--------------------------------|--------------|--------------|
    | org.gradle.status              | release      |              |
    | org.gradle.category            | library      | library      |
    | org.gradle.libraryelements     | jar          | jar          |
    | org.gradle.usage               | java-runtime | java-runtime |
    | org.gradle.dependency.bundling |              | external     |
    | org.gradle.jvm.environment     |              | standard-jvm |
    | org.gradle.jvm.version         |              | 17           |

org.flywaydb:flyway-core:11.7.2
\--- org.flywaydb:flyway-mysql:11.7.2
     \--- runtimeClasspath (requested org.flywaydb:flyway-mysql)

org.flywaydb:flyway-core -> 11.7.2
\--- runtimeClasspath

A web-based, searchable dependency report is available by adding the --scan option.

BUILD SUCCESSFUL in 4s
1 actionable task: 1 executed
```

기존 과거 로그: [historical-dependency.txt](historical-dependency.txt). 원본은 로컬 학습 자료(gitignore 된 블로그 연재 도구 폴더)의 로그였고 줄 끝 공백만 제거해 복사했다. 이 파일 자체에 checkout SHA/명령이 없다는 한계는 위의 git 설정 대조와 구분한다.

## 두 jar 직접 실행

각 jar의 `ChecksumCalculator.calculate(new StringResource(input))`를 직접 호출했다. Java probe는 이 문서 아래에 전부 남겼다. `javap -c -p`로 `ChecksumCalculator`와 `StringResource`를 열어 기존 바이트코드 기록과 일치함도 확인했다. calculator는 첫 줄의 BOM을 제거한 후 `BufferedReader.readLine()`의 줄 텍스트를 UTF-8 바이트로 CRC32에 넣는다. 줄 끝 문자는 입력하지 않는다. 주석을 SQL로 파싱해서 제거하거나 공백을 trim하지 않는다.

| Maven artifact | 로컬 jar SHA-256 |
|---|---|
| `org.flywaydb:flyway-core:11.7.2` | `ff01fab3bcfd79e9345df191ecf82cbe1f9f65a757266158bd691564b1c1282c` |
| `org.flywaydb:flyway-core:12.4.0` | `c3f93df1f3e0c9d14652adb5ade97401f2ede503859ac693c2af5c06e2797799` |

아래 입력은 Java 문자열 리터럴 표기다. `\n`은 LF, `\r\n`은 CRLF이며, `space_only_line`의 중간 줄에는 ASCII space 2개가 있다. 반환값은 signed Java `int`다.

| 이름 | 정확한 입력 | 11.7.2 | 12.4.0 |
|---|---|---:|---:|
| baseline | `"-- note\nCREATE TABLE t (id INT);\n"` | -765512047 | -765512047 |
| same | `"-- note\nCREATE TABLE t (id INT);\n"` | -765512047 | -765512047 |
| comment | `"-- edited\nCREATE TABLE t (id INT);\n"` | -244944236 | -244944236 |
| blank_line | `"-- note\n\nCREATE TABLE t (id INT);\n"` | -765512047 | -765512047 |
| crlf | `"-- note\r\nCREATE TABLE t (id INT);\r\n"` | -765512047 | -765512047 |
| no_final_newline | `"-- note\nCREATE TABLE t (id INT);"` | -765512047 | -765512047 |
| space_only_line | `"-- note\n  \nCREATE TABLE t (id INT);\n"` | -288562834 | -288562834 |
| sql_change | `"-- note\nCREATE TABLE t (id BIGINT);\n"` | 939283386 | 939283386 |

이는 8개 입력에서 관찰한 calculator 결과다. 모든 주석 변경이 반드시 다른 CRC32를 만든다는 일반 명제로 확장하지 않는다. 빈 줄은 빈 바이트 배열을 넣으므로 위 입력에서는 CRC가 그대로다. 두 버전 전체 구현의 동등성이나 실제 migration validation 결과를 검증한 것은 아니다. 적용된 DB의 schema history와 비교하는 `flyway validate`는 실행하지 않았다.

재현 시 아래를 `Blog32Checksum.java`로 저장하고 확인할 jar를 `FLYWAY_JAR`로 지정한다. 프로젝트 소스·DB·Gradle task를 실행하지 않는 독립 probe다.

```sh
shasum -a 256 "$FLYWAY_JAR"
javap -c -p -classpath "$FLYWAY_JAR" org.flywaydb.core.internal.resolver.ChecksumCalculator org.flywaydb.core.internal.resource.StringResource
javac -cp "$FLYWAY_JAR" -d "$PROBE_CLASSES" Blog32Checksum.java
java -cp "$PROBE_CLASSES:$FLYWAY_JAR" Blog32Checksum
```

`PROBE_CLASSES`는 미리 만든 빈 임시 디렉터리다. 두 jar 각각 컴파일/실행했으며 모두 종료 코드 0이었다.

```java
import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.flywaydb.core.internal.resource.StringResource;
public class Blog32Checksum {
 public static void main(String[] args) {
  String b="-- note\nCREATE TABLE t (id INT);\n";
  String[] names={"baseline","same","comment","blank_line","crlf","no_final_newline","space_only_line","sql_change"};
  String[] values={b,b,b.replace("note","edited"),b.replace("\nCREATE","\n\nCREATE"),b.replace("\n","\r\n"),b.substring(0,b.length()-1),b.replace("\nCREATE","\n  \nCREATE"),b.replace("INT","BIGINT")};
  for(int i=0;i<names.length;i++) System.out.println(names[i]+"="+ChecksumCalculator.calculate(new StringResource(values[i])));
 }
}
```
