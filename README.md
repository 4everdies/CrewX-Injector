# CrewX Injector

Source do [CrewX Injector](https://github.com/4everdies/CrewX-Injector) para Minecraft 1.8.9. Esta versão usa a base injetável CrewX 2.3 e incorpora alterações da source Java 2.4. Os detalhes do port e das adaptações para cada client estão em [BUILD-RECrew.md](BUILD-RECrew.md).

## Compilar no Windows

Requisitos: Windows x64, JDK 17, JDK 8, Visual Studio com ferramentas C++ e CMake. O Gradle Wrapper acompanha a source. Para incluir o payload do OneClient com a hierarquia de classes verificada, instale e execute o OneClient 1.8.9 antes do build; o script procura o JAR do jogo na instalação local. O payload do CheatBreaker é opcional e só é gerado quando o patch auditado está presente.

Abra o PowerShell na raiz do projeto e execute, ajustando os caminhos dos JDKs:

```powershell
.\Build-Injector.ps1 -Jdk17 'C:\caminho\jdk-17' -Jdk8 'C:\caminho\jdk-8'
```

O loader compilado fica em `dist/CrewXInjector.exe`. Para compilar apenas o projeto Java:

```powershell
.\gradlew.bat assemble
```

Os arquivos gerados por esses comandos estão no `.gitignore` e não devem entrar no repositório. O arquivo `libs/mcef-legacy.jar` é uma dependência do build e permanece na source.

## Origem e licenças

O CrewX original é de [4everdies/CrewX](https://github.com/4everdies/CrewX). Os créditos e a licença da base estão preservados em [LICENSE](LICENSE); este repositório contém adaptações para o loader injetável. O MCEF incluído em `libs/mcef-legacy.jar` deriva do projeto [montoyo/mcef](https://github.com/montoyo/mcef). O JAR local não contém aviso de licença nem identifica a versão exata; confirme sua procedência e os termos de redistribuição antes de publicar releases que o incluam. Consulte também a [licença do projeto MCEF](https://github.com/montoyo/mcef/blob/master/LICENSE).
