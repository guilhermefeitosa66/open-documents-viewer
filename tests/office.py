"""Office documents made on the fly for the tests (hand-written XML)."""

import zipfile

PNG = open(__file__.replace("office.py", "fixtures/images/a-red.png"), "rb").read()
WNS = ('xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" '
       'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
       'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
       'xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" '
       'xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"')


def _p(text, style=None, extra_ppr="", runs=None):
    ppr = (f'<w:pPr>{f"<w:pStyle w:val=\"{style}\"/>" if style else ""}{extra_ppr}</w:pPr>'
           if style or extra_ppr else "")
    body = runs if runs is not None else f"<w:r><w:t xml:space=\"preserve\">{text}</w:t></w:r>"
    return f"<w:p>{ppr}{body}</w:p>"


def make_docx(path):
    numbered = '<w:numPr><w:ilvl w:val="0"/><w:numId w:val="2"/></w:numPr>'
    bullet = '<w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr>'
    body = "".join([
        _p("Report Title", "Title"),
        _p("Introduction", "Heading1"),
        _p(None, runs='<w:r><w:t xml:space="preserve">Plain </w:t></w:r>'
                      '<w:r><w:rPr><w:b/></w:rPr><w:t>bold</w:t></w:r>'
                      '<w:r><w:t xml:space="preserve">, </w:t></w:r>'
                      '<w:r><w:rPr><w:i/><w:color w:val="B5502B"/></w:rPr><w:t>red italic</w:t></w:r>'
                      '<w:r><w:t xml:space="preserve"> and </w:t></w:r>'
                      '<w:hyperlink r:id="rLink"><w:r><w:t>a link</w:t></w:r></w:hyperlink>'
                      '<w:r><w:t>.</w:t></w:r>'
                      '<w:r><w:footnoteReference w:id="1"/></w:r>'
                      '<w:r><w:fldChar w:fldCharType="begin"/></w:r>'
                      '<w:r><w:instrText>PAGE \\* MERGEFORMAT</w:instrText></w:r>'
                      '<w:r><w:fldChar w:fldCharType="separate"/></w:r>'
                      '<w:r><w:t>7</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r>'),
        _p("Centered line", extra_ppr='<w:jc w:val="center"/>'),
        _p("First step", extra_ppr=numbered),
        _p("Second step", extra_ppr=numbered),
        _p("A bullet", extra_ppr=bullet),
        '<w:tbl><w:tr><w:trPr><w:tblHeader/></w:trPr>'
        '<w:tc><w:p><w:r><w:t>Name</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Size</w:t></w:r></w:p></w:tc></w:tr>'
        '<w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:r><w:t>merged</w:t></w:r></w:p></w:tc></w:tr>'
        '</w:tbl>',
        _p(None, runs='<w:r><w:br w:type="page"/></w:r>'),
        _p("Details", "Heading2"),
        _p(None, runs='<w:r><w:drawing><wp:inline><a:graphic><a:graphicData><pic:pic><pic:blipFill>'
                      '<a:blip r:embed="rImg"/></pic:blipFill></pic:pic></a:graphicData></a:graphic>'
                      '</wp:inline></w:drawing></w:r>'),
        _p(None, runs='<w:r><w:rPr><w:vanish/></w:rPr><w:t>hidden text</w:t></w:r>'),
        '<w:sectPr><w:headerReference w:type="default" r:id="rHeader"/>'
        '<w:footerReference w:type="default" r:id="rFooter"/></w:sectPr>',
    ])
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("[Content_Types].xml", '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/'
                   'package/2006/content-types"/>')
        z.writestr("word/document.xml", f'<?xml version="1.0"?><w:document {WNS}><w:body>{body}</w:body></w:document>')
        z.writestr("word/styles.xml", f'<?xml version="1.0"?><w:styles {WNS}>'
                   '<w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults>'
                   '<w:style w:styleId="Title"><w:name w:val="Title"/></w:style>'
                   '<w:style w:styleId="Heading1"><w:name w:val="heading 1"/></w:style>'
                   '<w:style w:styleId="Heading2"><w:name w:val="heading 2"/></w:style></w:styles>')
        z.writestr("word/numbering.xml", f'<?xml version="1.0"?><w:numbering {WNS}>'
                   '<w:abstractNum w:abstractNumId="10"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/>'
                   '<w:lvlText w:val="•"/></w:lvl></w:abstractNum>'
                   '<w:abstractNum w:abstractNumId="20"><w:lvl w:ilvl="0"><w:start w:val="1"/>'
                   '<w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl></w:abstractNum>'
                   '<w:num w:numId="1"><w:abstractNumId w:val="10"/></w:num>'
                   '<w:num w:numId="2"><w:abstractNumId w:val="20"/></w:num></w:numbering>')
        z.writestr("word/footnotes.xml", f'<?xml version="1.0"?><w:footnotes {WNS}>'
                   '<w:footnote w:type="separator" w:id="-1"><w:p><w:r><w:t>---</w:t></w:r></w:p></w:footnote>'
                   '<w:footnote w:id="1"><w:p><w:r><w:t>A footnote.</w:t></w:r></w:p></w:footnote></w:footnotes>')
        z.writestr("word/header1.xml", f'<?xml version="1.0"?><w:hdr {WNS}><w:p><w:r><w:t>Company header</w:t></w:r>'
                   '</w:p></w:hdr>')
        z.writestr("word/footer1.xml", f'<?xml version="1.0"?><w:ftr {WNS}><w:p><w:r><w:t>Page footer</w:t></w:r>'
                   '</w:p></w:ftr>')
        z.writestr("word/media/image1.png", PNG)
        z.writestr("word/_rels/document.xml.rels", '<?xml version="1.0"?><Relationships xmlns="http://schemas.'
                   'openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="rLink" Type="hyperlink" Target="https://libre-apps.org" TargetMode="External"/>'
                   '<Relationship Id="rImg" Type="image" Target="media/image1.png"/>'
                   '<Relationship Id="rHeader" Type="header" Target="header1.xml"/>'
                   '<Relationship Id="rFooter" Type="footer" Target="footer1.xml"/></Relationships>')
    return path


ODF_NS = ('xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" '
          'xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" '
          'xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" '
          'xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" '
          'xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" '
          'xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" '
          'xmlns:xlink="http://www.w3.org/1999/xlink"')


def make_odt(path):
    content = f"""<?xml version="1.0" encoding="UTF-8"?>
<office:document-content {ODF_NS}>
<office:automatic-styles>
  <style:style style:name="B" style:family="text"><style:text-properties fo:font-weight="bold"/></style:style>
  <style:style style:name="IR" style:family="text"><style:text-properties fo:font-style="italic" fo:color="#b5502b"/></style:style>
  <style:style style:name="H" style:family="text"><style:text-properties text:display="none"/></style:style>
  <style:style style:name="C" style:family="paragraph"><style:paragraph-properties fo:text-align="center"/></style:style>
  <style:style style:name="PB" style:family="paragraph"><style:paragraph-properties fo:break-before="page"/></style:style>
  <text:list-style style:name="Num"><text:list-level-style-number text:level="1" style:num-format="1" style:num-suffix="."/></text:list-style>
  <text:list-style style:name="Bul"><text:list-level-style-bullet text:level="1" text:bullet-char="•"/></text:list-style>
</office:automatic-styles>
<office:body><office:text>
  <text:h text:outline-level="1">Report Title</text:h>
  <text:h text:outline-level="2">Introduction</text:h>
  <text:p>Plain <text:span text:style-name="B">bold</text:span>, <text:span text:style-name="IR">red italic</text:span> and <text:a xlink:href="https://libre-apps.org">a link</text:a>.<text:note text:note-class="footnote"><text:note-citation>1</text:note-citation><text:note-body><text:p>A footnote.</text:p></text:note-body></text:note></text:p>
  <text:p text:style-name="C">Centered<text:s text:c="2"/>line</text:p>
  <text:list text:style-name="Num"><text:list-item><text:p>First step</text:p></text:list-item><text:list-item><text:p>Second step</text:p></text:list-item></text:list>
  <text:list text:style-name="Bul"><text:list-item><text:p>A bullet</text:p></text:list-item></text:list>
  <table:table><table:table-row><table:table-cell><text:p>Name</text:p></table:table-cell><table:table-cell><text:p>Size</text:p></table:table-cell></table:table-row>
  <table:table-row><table:table-cell table:number-columns-spanned="2"><text:p>merged</text:p></table:table-cell><table:covered-table-cell/></table:table-row></table:table>
  <text:h text:style-name="PB" text:outline-level="2">Details</text:h>
  <text:p><draw:frame><draw:image xlink:href="Pictures/red.png"/></draw:frame></text:p>
  <text:p><text:span text:style-name="H">hidden text</text:span></text:p>
</office:text></office:body></office:document-content>"""
    styles = f"""<?xml version="1.0" encoding="UTF-8"?>
<office:document-styles {ODF_NS}><office:master-styles><style:master-page style:name="Standard">
<style:header><text:p>Company header</text:p></style:header><style:footer><text:p>Page footer</text:p></style:footer>
</style:master-page></office:master-styles></office:document-styles>"""
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("mimetype", "application/vnd.oasis.opendocument.text")
        z.writestr("content.xml", content)
        z.writestr("styles.xml", styles)
        z.writestr("Pictures/red.png", PNG)
    return path
