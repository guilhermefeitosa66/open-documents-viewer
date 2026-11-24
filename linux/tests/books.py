"""Small e-books made on the fly for the tests (no third-party content)."""

import base64
import zipfile

PNG = open(__file__.replace("books.py", "fixtures/images/a-red.png"), "rb").read()


def make_epub(path, version=3, drm=False):
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("mimetype", "application/epub+zip")
        z.writestr("META-INF/container.xml", """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""")
        if drm:
            z.writestr("META-INF/encryption.xml", '<encryption><EncryptedData><CipherReference URI="OEBPS/c1.xhtml"/>'
                                                  "</EncryptedData></encryption>")
        nav_item = '<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>'
        z.writestr("OEBPS/content.opf", f"""<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="{version}.0">
  <metadata><title>Test book</title></metadata>
  <manifest>
    {nav_item if version == 3 else ''}
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="c1" href="text/c1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="text/c2.xhtml" media-type="application/xhtml+xml"/>
    <item id="img" href="images/red.png" media-type="image/png"/>
  </manifest>
  <spine toc="ncx"><itemref idref="c1"/><itemref idref="c2"/></spine>
</package>""")
        z.writestr("OEBPS/text/c1.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><body>
<h1>Chapter One</h1><p>First words. See <a href="c2.xhtml#scene">the second scene</a>.</p>
""" + "<p>Filler paragraph for scrolling.</p>" * 80 + """
<p><img src="../images/red.png" alt="red"/></p></body></html>""")
        z.writestr("OEBPS/text/c2.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><body>
<h1>Chapter Two</h1><p>Middle.</p>""" + "<p>More filler.</p>" * 80 + """<h2 id="scene">A scene</h2>
""" + "<p>Scene text.</p>" * 80 + """<p>The end.</p></body></html>""")
        z.writestr("OEBPS/images/red.png", PNG)
        z.writestr("OEBPS/nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<body><nav epub:type="toc"><ol>
<li><a href="text/c1.xhtml">One (from the nav)</a></li>
<li><a href="text/c2.xhtml">Two (from the nav)</a><ol><li><a href="text/c2.xhtml#scene">Scene</a></li></ol></li>
</ol></nav></body></html>""")
        z.writestr("OEBPS/toc.ncx", """<?xml version="1.0"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
<navPoint id="a"><navLabel><text>One (from the NCX)</text></navLabel><content src="text/c1.xhtml"/></navPoint>
<navPoint id="b"><navLabel><text>Two (from the NCX)</text></navLabel><content src="text/c2.xhtml"/></navPoint>
</navMap></ncx>""")
    return path


def make_fb2(path):
    data = base64.b64encode(PNG).decode()
    path.write_text(f"""<?xml version="1.0" encoding="utf-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
  <description><title-info><book-title>Hidden metadata</book-title></title-info></description>
  <body>
    <section><title><p>Part One</p></title>
      <p>Some <emphasis>words</emphasis> and <strong>more</strong>.</p>
      <image l:href="#cover"/>
      <section><title><p>Inner</p></title><p>Deep.</p></section>
    </section>
  </body>
  <binary id="cover" content-type="image/png">{data}</binary>
</FictionBook>""", encoding="utf-8")
    return path
