# Sentinela Crossland: Sistema de Vigilância e Deteção Automática On-Device

Aplicação Android nativa (Kotlin) que atua como um sistema de sentinela e vigilância contínua para deteção estrita e alarme do veículo:
- **Modelo:** Opel Crossland X (1.ª geração).
- **Pintura Bicolor:** Tejadilho, espelhos e pilares pretos; carroçaria em cinzento claro.
- **Matrícula:** Portuguesa **`28-VE-91`** (com normalização e tolerância a ruído OCR).
- **Ângulo Preferencial:** Vista lateral/perfil a partir de perspetiva superior/elevada (janela apontada para a via).

---

## 1. Arquitetura e Eficiência Energética

Para permitir uma operação contínua de várias horas na janela sem sobreaquecimento ou drenagem acelerada da bateria:

1. **Nível 1 (Baixo Consumo - Motion Check na ROI):**
   - Resolução moderada (720p / 1280x720) via CameraX `ImageAnalysis`.
   - Amostragem throttled a ~4 FPS.
   - Cálculo direto no canal de luminância (Plano Y YUV) dentro da Região de Interesse (ROI) retangular desenhada pelo utilizador sobre a estrada.
   - Algoritmo de grelha pré-alocada com zero alocação no heap (zero pressão no Garbage Collector).
   - Se não houver variação substancial na via, o frame é imediatamente libertado em menos de 2ms.

2. **Nível 2 (Inferência On-Device Completa):**
   - Acionado apenas quando há movimento na via.
   - **Verificação Bicolor:** Confirma o rácio de escuridão no terço superior (tejadilho preto) e luminância média-alta neutra na secção inferior (carroçaria cinzento claro).
   - **OCR On-Device (Google ML Kit):** Reconhecimento de texto local (sem chamadas cloud) à procura da matrícula `28-VE-91`, utilizando correspondência Regex e tolerância de distância Levenshtein.

3. **Modo OLED Eco (Proteção contra Burn-in e Poupança Máxima):**
   - Ecrã preto puro (`#000000`) desliga os pixéis emissivos em ecrãs OLED/AMOLED.
   - Brilho de hardware do ecrã reduzido ao mínimo (0.01).
   - Acorda com um simples toque duplo no ecrã.

4. **Alarme Crítico Full-Screen (Estilo Chamada Telefónica Urgente):**
   - Disparo via `FullScreenIntent` com prioridade máxima (`CATEGORY_CALL`).
   - Acorda o ecrã imediatamente através de `turnScreenOn(true)` e `setShowWhenLocked(true)`, sobrepondo-se ao ecrã de bloqueio.
   - Som de toque/alarme contínuo em volume máximo (`STREAM_ALARM`) contornando o modo 'Não Incomodar'.
   - Vibração insistente contínua.
   - Registo fotográfico automático (snapshot JPEG) no armazenamento interno da app, exibido na `AlarmActivity`.

---

## 2. Estrutura do Projeto

```
localizador/
├── gradlew
├── gradlew.bat
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── res/
        │   └── java/com/sentinela/crossland/
        │       ├── SentinelaApp.kt
        │       ├── data/
        │       │   ├── Models.kt
        │       │   └── AppPreferences.kt
        │       ├── vision/
        │       │   ├── MotionDetector.kt
        │       │   ├── VehicleProfileAnalyzer.kt
        │       │   ├── LicensePlateRecognizer.kt
        │       │   └── DetectionPipeline.kt
        │       ├── alarm/
        │       │   ├── AlarmController.kt
        │       │   └── NotificationHelper.kt
        │       ├── service/
        │       │   └── CameraService.kt
        │       └── ui/
        │           ├── MainActivity.kt
        │           ├── AlarmActivity.kt
        │           ├── components/
        │           │   ├── CameraPreviewView.kt
        │           │   ├── RoiOverlayView.kt
        │           │   └── OledEcoOverlay.kt
        │           └── theme/
        └── test/
            └── java/com/sentinela/crossland/
                └── LicensePlateMatcherTest.kt
```

---

## 3. Instruções de Compilação do APK

### Opção A: Via Linha de Comandos (Gradle Wrapper)
Certifica-te de que tens o JDK 17 ou superior instalado:

1. **Compilar em Modo Debug (recomendado para testes):**
   ```bash
   # No Windows:
   .\gradlew.bat assembleDebug

   # No Linux / macOS:
   chmod +x gradlew
   ./gradlew assembleDebug
   ```
   O APK gerado estará em:
   `app/build/outputs/apk/debug/app-debug.apk`

2. **Compilar em Modo Release:**
   ```bash
   .\gradlew.bat assembleRelease
   ```
   O APK gerado estará em:
   `app/build/outputs/apk/release/app-release-unsigned.apk`

---

### Opção B: Via Android Studio
1. Abre o **Android Studio**.
2. Seleciona **Open** e escolhe a pasta `c:\Users\mapga\Desktop\localizador`.
3. Aguarda que a sincronização do Gradle (`Sync Project with Gradle Files`) termine.
4. No menu superior, clica em:
   **Build** > **Build Bundle(s) / APK(s)** > **Build APK(s)**.
5. Quando terminar, clica no link **locate** que aparece na notificação para aceder ao ficheiro `.apk`.

---

## 4. Instalação e Configuração no Dispositivo

1. **Instalar o APK no smartphone:**
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   (ou transfere o ficheiro `.apk` para o telefone e instala via gestor de ficheiros).

2. **Permissões Críticas:**
   - **Câmara:** Conceder na inicialização.
   - **Notificações:** Conceder para alertas e Foreground Service.
   - **Acesso ao Modo Não Incomodar (DND):**
     Vai a *Definições > Aplicações > Acesso Especial > Acesso a Não Incomodar* e autoriza a app **Sentinela Crossland** para que o alarme toque mesmo em silêncio.
   - **Aparecer sobre outras aplicações / Ecrã Inteiro:**
     Garantir que a opção de ecrã inteiro e sobreposição está ativa nas definições de aplicações.
   - **Otimização de Bateria:**
     Define a app para **Sem Restrições** (*Unrestricted*) para que o Android não suspenda o Foreground Service durante a noite.

---

## 5. Como Operar a Sentinela

1. Coloca o smartphone num suporte estável na janela virado para a rua.
2. Abre a app **Sentinela Crossland**.
3. Clica no ícone de mira/quadrado (topo direito) para **Ajustar ROI**:
   - Arrasta a caixa verde e os cantos de forma a cobrir **apenas a faixa de rodagem** da estrada.
   - Exclui janelas de vizinhos, árvores que abanam com o vento ou o céu.
4. Clica em **INICIAR SENTINELA**:
   - O serviço em primeiro plano inicia a monitorização da via a 4 FPS.
5. Clica no ícone de **Lua/Modo Escuro** para ativar o **Modo OLED Eco**:
   - O ecrã desliga os pixéis ficando a preto, reduzindo o calor e o consumo de energia ao mínimo.
6. Se o Opel Crossland X (28-VE-91) passar na via:
   - O ecrã acorda imediatamente com o ecrã de chamada em vermelho pulsante.
   - Toca o alarme no volume máximo e vibra intensamente.
   - A foto do momento fica guardada e visível no ecrã.
   - Clica no botão grande **DESLIGAR ALARME** para silenciar.
