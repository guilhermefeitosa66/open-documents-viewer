"""Translations: English (the source strings), Brazilian Portuguese and Spanish.

A plain dictionary instead of gettext: no build step, nothing to compile, and
the whole catalogue costs a few kilobytes at start-up. ``_("text")`` returns
the translation for the user's language, or the English text. Placeholders
use str.format: ``_("Not found: {path}").format(path=p)``.
"""

import os

SUPPORTED = ("en", "pt", "es")


def detect(env=None) -> str:
    env = os.environ if env is None else env
    for var in ("ODV_LANG", "LANGUAGE", "LC_ALL", "LC_MESSAGES", "LANG"):
        value = env.get(var, "")
        for part in value.split(":"):
            code = part.split(".")[0].split("_")[0].lower()
            if code in SUPPORTED:
                return code
            if code and code not in ("c", "posix"):
                break
    return "en"


LANG = detect()

# English -> {language: translation}
CATALOG: dict[str, dict[str, str]] = {
    # welcome / window
    "Open a file or a folder. Nothing is edited, nothing leaves this computer.": {
        "pt": "Abra um arquivo ou uma pasta. Nada é editado, nada sai deste computador.",
        "es": "Abre un archivo o una carpeta. Nada se edita, nada sale de este equipo."},
    "Open File…": {"pt": "Abrir arquivo…", "es": "Abrir archivo…"},
    "Open Folder…": {"pt": "Abrir pasta…", "es": "Abrir carpeta…"},
    "or drop files and folders anywhere in this window": {
        "pt": "ou solte arquivos e pastas em qualquer lugar desta janela",
        "es": "o suelta archivos y carpetas en cualquier lugar de esta ventana"},
    "Recent": {"pt": "Recentes", "es": "Recientes"},
    "Clear": {"pt": "Limpar", "es": "Borrar"},
    "Today": {"pt": "Hoje", "es": "Hoy"},
    "Yesterday": {"pt": "Ontem", "es": "Ayer"},
    "{n} days ago": {"pt": "há {n} dias", "es": "hace {n} días"},
    "No file open": {"pt": "Nenhum arquivo aberto", "es": "Ningún archivo abierto"},
    "Pick a file in the sidebar.": {"pt": "Escolha um arquivo na barra lateral.",
                                    "es": "Elige un archivo en la barra lateral."},
    "Show folder (F9)": {"pt": "Mostrar pasta (F9)", "es": "Mostrar carpeta (F9)"},
    "Open": {"pt": "Abrir", "es": "Abrir"},
    "Open a file (Ctrl+O)": {"pt": "Abrir um arquivo (Ctrl+O)", "es": "Abrir un archivo (Ctrl+O)"},
    "Find": {"pt": "Buscar", "es": "Buscar"},
    "Reload": {"pt": "Recarregar", "es": "Recargar"},
    "Split View": {"pt": "Dividir tela", "es": "Dividir vista"},
    "Preferences": {"pt": "Preferências", "es": "Preferencias"},
    "Keyboard Shortcuts": {"pt": "Atalhos de teclado", "es": "Atajos de teclado"},
    "About {app}": {"pt": "Sobre o {app}", "es": "Acerca de {app}"},
    "Main menu": {"pt": "Menu principal", "es": "Menú principal"},
    "Split view (Ctrl+\\)": {"pt": "Dividir tela (Ctrl+\\)", "es": "Dividir vista (Ctrl+\\)"},
    "Not found: {path}": {"pt": "Não encontrado: {path}", "es": "No encontrado: {path}"},
    "Open File": {"pt": "Abrir arquivo", "es": "Abrir archivo"},
    "Open Folder": {"pt": "Abrir pasta", "es": "Abrir carpeta"},
    "Formatted": {"pt": "Formatado", "es": "Formateado"},
    "Source": {"pt": "Fonte", "es": "Fuente"},
    "Table": {"pt": "Tabela", "es": "Tabla"},
    "Formatted view (Ctrl+E)": {"pt": "Visão formatada (Ctrl+E)", "es": "Vista formateada (Ctrl+E)"},
    "Source view (Ctrl+E)": {"pt": "Código-fonte (Ctrl+E)", "es": "Código fuente (Ctrl+E)"},
    # shortcuts window
    "Files": {"pt": "Arquivos", "es": "Archivos"},
    "View": {"pt": "Exibir", "es": "Ver"},
    "Open file": {"pt": "Abrir arquivo", "es": "Abrir archivo"},
    "Open folder": {"pt": "Abrir pasta", "es": "Abrir carpeta"},
    "Close tab": {"pt": "Fechar aba", "es": "Cerrar pestaña"},
    "Formatted / source": {"pt": "Formatado / fonte", "es": "Formateado / fuente"},
    "Other pane": {"pt": "Outro painel", "es": "Otro panel"},
    "Show folder": {"pt": "Mostrar pasta", "es": "Mostrar carpeta"},
    "Next tab": {"pt": "Próxima aba", "es": "Pestaña siguiente"},
    "Previous tab": {"pt": "Aba anterior", "es": "Pestaña anterior"},
    # document
    "Previous match (Shift+Enter)": {"pt": "Resultado anterior (Shift+Enter)",
                                     "es": "Resultado anterior (Mayús+Intro)"},
    "Next match (Enter)": {"pt": "Próximo resultado (Enter)", "es": "Resultado siguiente (Intro)"},
    "This is a binary file": {"pt": "Este é um arquivo binário", "es": "Este es un archivo binario"},
    "This file type cannot be shown yet.": {"pt": "Este tipo de arquivo ainda não pode ser exibido.",
                                            "es": "Este tipo de archivo aún no se puede mostrar."},
    "Could not open this file": {"pt": "Não foi possível abrir este arquivo",
                                 "es": "No se pudo abrir este archivo"},
    "Large file ({size}): rows appear as they load ({percent}%). Scrolling and selecting already work.": {
        "pt": "Arquivo grande ({size}): as linhas aparecem conforme carregam ({percent}%). "
              "Rolar e selecionar já funcionam.",
        "es": "Archivo grande ({size}): las filas aparecen a medida que se cargan ({percent}%). "
              "Ya puedes desplazarte y seleccionar."},
    "Stop loading": {"pt": "Parar de carregar", "es": "Dejar de cargar"},
    "Showing the first {shown} of {total}.": {"pt": "Mostrando os primeiros {shown} de {total}.",
                                              "es": "Mostrando los primeros {shown} de {total}."},
    "Reloaded: the file changed on disk.": {"pt": "Recarregado: o arquivo mudou no disco.",
                                           "es": "Recargado: el archivo cambió en el disco."},
    "This file was deleted or moved. What you see is the last version read.": {
        "pt": "Este arquivo foi apagado ou movido. O que você vê é a última versão lida.",
        "es": "Este archivo se borró o se movió. Lo que ves es la última versión leída."},
    # find in folder
    "Search in folder": {"pt": "Buscar na pasta", "es": "Buscar en la carpeta"},
    "Search in folder (Ctrl+Shift+F)": {"pt": "Buscar na pasta (Ctrl+Shift+F)",
                                        "es": "Buscar en la carpeta (Ctrl+Mayús+F)"},
    "Type at least 2 characters.": {"pt": "Digite pelo menos 2 caracteres.",
                                    "es": "Escribe al menos 2 caracteres."},
    "Searching…": {"pt": "Buscando…", "es": "Buscando…"},
    "No matches.": {"pt": "Nada encontrado.", "es": "Sin resultados."},
    "{hits} matches in {files} files…": {"pt": "{hits} resultados em {files} arquivos…",
                                         "es": "{hits} resultados en {files} archivos…"},
    "{hits} matches in {files} files.": {"pt": "{hits} resultados em {files} arquivos.",
                                         "es": "{hits} resultados en {files} archivos."},
    "{hits} matches in {files} files (showing the first ones).": {
        "pt": "{hits} resultados em {files} arquivos (mostrando os primeiros).",
        "es": "{hits} resultados en {files} archivos (mostrando los primeros)."},
    # quick open / command palette
    "Go to file": {"pt": "Ir para arquivo", "es": "Ir a archivo"},
    "Run a command": {"pt": "Executar um comando", "es": "Ejecutar un comando"},
    "Listing files…": {"pt": "Listando arquivos…", "es": "Listando archivos…"},
    "{n} items": {"pt": "{n} itens", "es": "{n} elementos"},
    "New window": {"pt": "Nova janela", "es": "Nueva ventana"},
    "Quit": {"pt": "Sair", "es": "Salir"},
    "Theme: {name}": {"pt": "Tema: {name}", "es": "Tema: {name}"},
    "Background picture: on/off": {"pt": "Imagem de fundo: ligar/desligar",
                                   "es": "Imagen de fondo: activar/desactivar"},
    # outline
    "Outline": {"pt": "Sumário", "es": "Índice"},
    "Outline (Ctrl+Shift+L)": {"pt": "Sumário (Ctrl+Shift+L)", "es": "Índice (Ctrl+Mayús+L)"},
    "Folder": {"pt": "Pasta", "es": "Carpeta"},
    "Show sidebar": {"pt": "Mostrar barra lateral", "es": "Mostrar barra lateral"},
    "Show sidebar (F9)": {"pt": "Mostrar barra lateral (F9)", "es": "Mostrar barra lateral (F9)"},
    "No outline for this document.": {"pt": "Este documento não tem sumário.",
                                      "es": "Este documento no tiene índice."},
    # links
    "Open this link?": {"pt": "Abrir este link?", "es": "¿Abrir este enlace?"},
    "It opens outside the app:\n{uri}": {"pt": "Ele abre fora do app:\n{uri}", "es": "Se abre fuera de la app:\n{uri}"},
    "Cancel": {"pt": "Cancelar", "es": "Cancelar"},
    "Open Link": {"pt": "Abrir link", "es": "Abrir enlace"},
    "Link target not found: {target}": {"pt": "Destino do link não encontrado: {target}",
                                        "es": "No se encontró el destino del enlace: {target}"},
    # preferences
    "Appearance": {"pt": "Aparência", "es": "Apariencia"},
    "Theme": {"pt": "Tema", "es": "Tema"},
    "Follows the system": {"pt": "Segue o sistema", "es": "Sigue el sistema"},
    "Light": {"pt": "Claro", "es": "Claro"},
    "Dark": {"pt": "Escuro", "es": "Oscuro"},
    "{name} theme": {"pt": "Tema {name}", "es": "Tema {name}"},
    "Background": {"pt": "Fundo", "es": "Fondo"},
    "Background picture": {"pt": "Imagem de fundo", "es": "Imagen de fondo"},
    "Shown on the start screen and behind the sidebar and tabs. Documents always sit on a solid colour.": {
        "pt": "Aparece na tela inicial e atrás da barra lateral e das abas. Os documentos ficam sempre "
              "sobre cor sólida.",
        "es": "Se muestra en la pantalla de inicio y detrás de la barra lateral y las pestañas. Los "
              "documentos siempre van sobre un color sólido."},
    "Files and folders": {"pt": "Arquivos e pastas", "es": "Archivos y carpetas"},
    "Reload files when they change": {"pt": "Recarregar arquivos quando mudarem",
                                      "es": "Recargar archivos cuando cambien"},
    "Keeps the scroll position. Turn off for files that change all the time.": {
        "pt": "Mantém a posição da rolagem. Desligue para arquivos que mudam o tempo todo.",
        "es": "Mantiene la posición de desplazamiento. Desactívalo para archivos que cambian todo el tiempo."},
    "Reopen the last session": {"pt": "Reabrir a última sessão", "es": "Reabrir la última sesión"},
    "The folder, tabs, split and scroll positions, when the app starts without files.": {
        "pt": "A pasta, as abas, a tela dividida e as posições de rolagem, quando o app abre sem arquivos.",
        "es": "La carpeta, las pestañas, la vista dividida y las posiciones, cuando la app se abre sin archivos."},
    # comfort
    "Reading": {"pt": "Leitura", "es": "Lectura"},
    "Text width for prose": {"pt": "Largura do texto corrido", "es": "Ancho del texto"},
    "Markdown and other formatted text.": {"pt": "Markdown e outros textos formatados.",
                                           "es": "Markdown y otros textos con formato."},
    "Narrow": {"pt": "Estreita", "es": "Estrecho"},
    "Medium": {"pt": "Média", "es": "Medio"},
    "Wide": {"pt": "Larga", "es": "Ancho"},
    "Full width": {"pt": "Largura total", "es": "Ancho completo"},
    "Line numbers in code": {"pt": "Números de linha no código", "es": "Números de línea en el código"},
    "Minimap in code": {"pt": "Minimapa no código", "es": "Minimapa en el código"},
    "Zoom {level}%": {"pt": "Zoom {level}%", "es": "Zoom {level}%"},
    "Zoom in": {"pt": "Aumentar zoom", "es": "Acercar"},
    "Zoom out": {"pt": "Diminuir zoom", "es": "Alejar"},
    "Normal size": {"pt": "Tamanho normal", "es": "Tamaño normal"},
    "Line wrap": {"pt": "Quebra de linha", "es": "Ajuste de línea"},
    "Line wrap on": {"pt": "Quebra de linha ligada", "es": "Ajuste de línea activado"},
    "Line wrap off": {"pt": "Quebra de linha desligada", "es": "Ajuste de línea desactivado"},
    "Full Screen": {"pt": "Tela cheia", "es": "Pantalla completa"},
    "Focus Mode": {"pt": "Modo foco", "es": "Modo concentración"},
    "Focus mode: Ctrl+Shift+Enter brings the bars back.": {
        "pt": "Modo foco: Ctrl+Shift+Enter traz as barras de volta.",
        "es": "Modo concentración: Ctrl+Mayús+Intro devuelve las barras."},
    # file actions
    "Copy Path": {"pt": "Copiar caminho", "es": "Copiar ruta"},
    "Path copied": {"pt": "Caminho copiado", "es": "Ruta copiada"},
    "Show in Folder": {"pt": "Mostrar na pasta", "es": "Mostrar en la carpeta"},
    "Open With…": {"pt": "Abrir com…", "es": "Abrir con…"},
    "Save a Copy…": {"pt": "Salvar uma cópia…", "es": "Guardar una copia…"},
    "Save a Copy": {"pt": "Salvar uma cópia", "es": "Guardar una copia"},
    "Saved a copy to {path}": {"pt": "Cópia salva em {path}", "es": "Copia guardada en {path}"},
    "Could not save the copy: {error}": {"pt": "Não foi possível salvar a cópia: {error}",
                                         "es": "No se pudo guardar la copia: {error}"},
    "A copy cannot replace the original.": {"pt": "Uma cópia não pode substituir o original.",
                                            "es": "Una copia no puede reemplazar el original."},
    "Print…": {"pt": "Imprimir…", "es": "Imprimir…"},
    "Page %N of %Q": {"pt": "Página %N de %Q", "es": "Página %N de %Q"},
    "Open the file once in this view before printing.": {
        "pt": "Abra o arquivo nesta visão antes de imprimir.",
        "es": "Abre el archivo en esta vista antes de imprimir."},
    "Properties": {"pt": "Propriedades", "es": "Propiedades"},
    "Name": {"pt": "Nome", "es": "Nombre"},
    "Size": {"pt": "Tamanho", "es": "Tamaño"},
    "Type": {"pt": "Tipo", "es": "Tipo"},
    "Modified": {"pt": "Modificado", "es": "Modificado"},
    "Permissions": {"pt": "Permissões", "es": "Permisos"},
    "Encoding": {"pt": "Codificação", "es": "Codificación"},
    "Lines": {"pt": "Linhas", "es": "Líneas"},
    # images
    "Image": {"pt": "Imagem", "es": "Imagen"},
    "Could not show this image": {"pt": "Não foi possível mostrar esta imagem",
                                  "es": "No se pudo mostrar esta imagen"},
    "Previous image (Left)": {"pt": "Imagem anterior (←)", "es": "Imagen anterior (←)"},
    "Next image (Right)": {"pt": "Próxima imagem (→)", "es": "Imagen siguiente (→)"},
    "Fit to window (F)": {"pt": "Ajustar à janela (F)", "es": "Ajustar a la ventana (F)"},
    "Actual size (1)": {"pt": "Tamanho real (1)", "es": "Tamaño real (1)"},
    "Fit": {"pt": "Ajustada", "es": "Ajustada"},
    # data tree
    "Tree": {"pt": "Árvore", "es": "Árbol"},
    "Copy the node path (Ctrl+C)": {"pt": "Copiar o caminho do nó (Ctrl+C)",
                                    "es": "Copiar la ruta del nodo (Ctrl+C)"},
    " (line {line})": {"pt": " (linha {line})", "es": " (línea {line})"},
    "Could not read the structure{where}: {error}. Showing the source.": {
        "pt": "Não foi possível ler a estrutura{where}: {error}. Mostrando o código-fonte.",
        "es": "No se pudo leer la estructura{where}: {error}. Se muestra el código fuente."},
    # about
    "A fast, read-only viewer for Markdown, text, code and data files.": {
        "pt": "Um visualizador rápido, só de leitura, para Markdown, texto, código e dados.",
        "es": "Un visor rápido, de solo lectura, para Markdown, texto, código y datos."},
    "Fast, offline, read-only. No ads, no account, no tracking.": {
        "pt": "Rápido, offline, só leitura. Sem anúncio, sem conta, sem rastreamento.",
        "es": "Rápido, sin conexión, solo lectura. Sin anuncios, sin cuenta, sin rastreo."},
}


def _(text: str) -> str:
    if LANG == "en":
        return text
    entry = CATALOG.get(text)
    return entry.get(LANG, text) if entry else text


def set_language(code: str) -> None:
    """For tests and the ODV_LANG override."""
    global LANG
    LANG = code if code in SUPPORTED else "en"
