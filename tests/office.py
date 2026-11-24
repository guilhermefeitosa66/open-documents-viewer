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
