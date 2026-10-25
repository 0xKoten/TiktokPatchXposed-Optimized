# TiktokPatchXposed (Edición Optimizada)

[繁體中文](README.md) | [English](README_EN.md) | [简体中文](README_ZH-CN.md) | [日本語](README_JA.md) | [Русский](README_RU.md) | [Español](README_ES.md)

> Este proyecto es una bifurcación profundamente optimizada y con funciones ampliadas basada en [hik0w/TiktokPatchXposed](https://github.com/hik0w/TiktokPatchXposed).  
> ¡Agradecimiento especial al autor original **hik0w** por la excelente arquitectura del módulo y su base de código abierto!

---

## 🌟 Novedades y Optimizaciones (What's New & Optimized)

Esta versión optimizada incluye correcciones integrales de interfaz, mejoras de diseño y nuevas funciones adaptadas a las versiones modernas de TikTok (47.5+):

1. **Eliminación Total de Bordes Negros (Pantalla Completa Inmersiva)**
   - Elimina las barras negras superior e inferior para una reproducción fluida a pantalla completa.
   - **Compatibilidad total con todas las pestañas del Feed**: "Para ti", "Siguiendo", "Amigos", etc. Las barras negras no vuelven a aparecer al cambiar de pestaña.
   - Soluciona el problema de borde visible del siguiente video más largo (Peeking edge).

2. **Pantalla Completa Inmersiva de Android**
   - **Ocultar Barra de Navegación del Sistema**: Oculta los 3 botones virtuales y la barra blanca de gestos de Android para evitar desplazamientos de pantalla.
   - **Ocultar Barra de Estado del Sistema**: Oculta la hora, batería e iconos de notificaciones.

3. **Transparencia Personalizada de Barras Superior e Inferior**
   - **Transparencia de pestañas superiores** (Para ti, Siguiendo, Amigos): Ajuste fluido de 0% a 100%.
   - **Transparencia de barra inferior** (Inicio, Amigos, Bandeja de entrada, Perfil): Ajuste fluido de 0% a 100%.
   - Evita que el fondo se restablezca a negro sólido al salir del modo "Pantalla limpia".

4. **Eliminación Independiente del Botón Central «+» de Publicación**
   - Elimina el botón central con un solo toque.
   - Las 4 pestañas restantes se redistribuyen de forma simétrica (25% cada una) en el ancho de la pantalla.

5. **Corrección de Reaparición de UI al Deslizar la Barra de Progreso**
   - En TikTok nativo, al arrastrar manualmente la barra de progreso, los botones laterales y el texto del autor reaparecían a la fuerza.
   - La versión optimizada intercepta el dibujado para mantener activas las configuraciones de ocultación y transparencia en todo momento.

6. **Botón LIVE Superior Izquierdo Personalizable**
   - Corrige el conflicto del módulo original donde el botón LIVE desaparecía y dejaba de funcionar al activar el módulo.
   - Mantiene activado por defecto el botón nativo de LIVE y añade un conmutador independiente en los ajustes para ocultarlo si se desea.

7. **Página Dedicada de Ajustes «Ocultación y Transparencia»**
   - Opciones de ocultación y controles deslizantes de transparencia organizados en una limpia interfaz de dos tarjetas.

8. **Icono en el Lanzador y Créditos Integrados**
   - Icono independiente en la pantalla de inicio para acceder directamente a la configuración del módulo.
   - Sección de agradecimientos con enlace al proyecto original.

9. **Soporte Multilingüe (14 Idiomas)**
   - Selector de idioma en tiempo real integrado: español, inglés, chino tradicional, chino simplificado, japonés, coreano, alemán, francés, ruso, etc.
   - Cambia de idioma al instante o sigue automáticamente el idioma del sistema.

---

## 🛠️ Funciones Principales del Módulo Original Conservadas

- 🚫 **Bloqueo Completo de Anuncios**: Elimina anuncios en el feed, anuncios de inicio y en búsquedas.
- 📥 **Descargas Sin Marca de Agua y en Máxima Calidad**: Descarga de videos en calidad original, extracción de audio y descarga por lotes de carruseles de fotos en alta resolución.
- 🔄 **Desplazamiento Automático Global**: Pasa al siguiente video automáticamente al finalizar la reproducción.
- 🎨 **Color de Énfasis y Tema AMOLED Puro**: Personalización de color y fondo negro AMOLED puro para ahorrar batería.
- 🌐 **Cambio de Región y Modo Sin Inicio de Sesión**: Desbloquea restricciones geográficas y omite avisos de inicio de sesión obligatorio.
- 💬 **Traducción Automática de Comentarios y Hashtags Ilimitados**: Traducción instantánea y eliminación del límite de hashtags al publicar.
- 🛡️ **Modo Invisible**: Oculta el historial de visualización de historias y las visitas al perfil.

---

## 📦 Compilación desde el Código Fuente

### Requisitos
- **JDK**: OpenJDK 17 o superior
- **Android SDK**: API 34+ (Build-Tools 34.0.0+)
- **Gradle**: 8.7 (Gradle Wrapper incluido)

### Comandos de Compilación
```bash
# Windows
.\gradlew.bat assembleRelease

# Linux / macOS
./gradlew assembleRelease
```

El archivo APK generado se encontrará en:
`app/build/outputs/apk/release/app-release.apk`

---

## ❤️ Agradecimientos (Acknowledgments)

- Proyecto Original: [hik0w/TiktokPatchXposed](https://github.com/hik0w/TiktokPatchXposed)
- ¡Gracias a **hik0w** y a todos los desarrolladores del ecosistema de código abierto Xposed / LSPosed!

---

## ⚖️ Descargo de Responsabilidad (Disclaimer)

Este proyecto se proporciona estrictamente con fines educativos y de investigación técnica. Respete las leyes locales y los términos de servicio de terceros.
