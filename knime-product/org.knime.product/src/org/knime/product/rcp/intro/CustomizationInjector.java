/*
	 * ------------------------------------------------------------------------
	 *  Copyright by KNIME AG, Zurich, Switzerland
	 *  Website: http://www.knime.com; Email: contact@knime.com
	 *
	 *  This program is free software; you can redistribute it and/or modify
	 *  it under the terms of the GNU General Public License, Version 3, as
	 *  published by the Free Software Foundation.
	 *
	 *  This program is distributed in the hope that it will be useful, but
	 *  WITHOUT ANY WARRANTY; without even the implied warranty of
	 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
	 *  GNU General Public License for more details.
	 *
	 *  You should have received a copy of the GNU General Public License
	 *  along with this program; if not, see <http://www.gnu.org/licenses>.
	 *
	 *  Additional permission under GNU GPL version 3 section 7:
	 *
	 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
	 *  Hence, KNIME and ECLIPSE are both independent programs and are not
	 *  derived from each other. Should, however, the interpretation of the
	 *  GNU GPL Version 3 ("License") under any applicable laws result in
	 *  KNIME and ECLIPSE being a combined program, KNIME AG herewith grants
	 *  you the additional permission to use and propagate KNIME together with
	 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
	 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
	 *  license terms of ECLIPSE themselves allow for the respective use and
	 *  propagation of ECLIPSE together with KNIME.
	 *
	 *  Additional permission relating to nodes for KNIME that extend the Node
	 *  Extension (and in particular that are based on subclasses of NodeModel,
	 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
	 *  standard APIs ("Nodes"):
	 *  Nodes are deemed to be separate and independent programs and to not be
	 *  covered works.  Notwithstanding anything to the contrary in the
	 *  License, the License does not apply to Nodes, you are not required to
	 *  license Nodes under the License, and you are granted a license to
	 *  prepare and propagate Nodes, in each case even if such Nodes are
	 *  propagated with or for interoperation with KNIME.  The owner of a Node
	 *  may freely choose the license terms applicable to such Node, including
	 *  when such Node is propagated with or for interoperation with KNIME.
	 * ---------------------------------------------------------------------
	 *
	 * History
	 *   26.03.2014 (thor): created
	 */
package org.knime.product.rcp.intro;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.knime.core.node.NodeLogger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.Text;
import org.xml.sax.SAXException;

/**
 * @deprecated no functionality on tile based welcome page soon to be replaced with customization updater
 */
@Deprecated
class CustomizationInjector extends AbstractInjector {

    private final Map<String, String> m_customizationInfo;

    private Node m_newIntroNode;

    private boolean m_newIntro;

    /**
     * Customizes the Welcome Page, i.e. adds a custom logo and a link to the "Where to go from here"-section and
     * replaces the text with a custom user text.
     *
     * @param templateFile
     * @param introFileLock
     * @param preferences
     * @param isFreshWorkspace
     * @param parserFactory
     * @param xpathFactory
     * @param transformerFactory
     * @param customizationInfo
     */
    CustomizationInjector(final File templateFile, final ReentrantLock introFileLock,
        final IEclipsePreferences preferences, final boolean isFreshWorkspace,
        final DocumentBuilderFactory parserFactory, final XPathFactory xpathFactory,
        final TransformerFactory transformerFactory, final Map<String, String> customizationInfo) {
        super(templateFile, introFileLock, preferences, isFreshWorkspace, parserFactory, xpathFactory,
            transformerFactory);
        m_customizationInfo = customizationInfo;

        if (m_customizationInfo.containsKey("IntroText")) { //$NON-NLS-1$
            //try to parse the provided replacement text
            try {
                DocumentBuilder parser = parserFactory.newDocumentBuilder();
                parser.setEntityResolver(EmptyDoctypeResolver.INSTANCE);
                Document introText = parser.parse(m_customizationInfo.get("IntroText")); //$NON-NLS-1$
                m_newIntroNode = ((Element)xpathFactory.newXPath().evaluate("//div[@id='intro-text']", //$NON-NLS-1$
                    introText.getDocumentElement(), XPathConstants.NODE)).cloneNode(true);
                m_newIntro = true;
            } catch (ParserConfigurationException | SAXException | IOException e) {
                NodeLogger.getLogger(getClass()).error(Messages.getString("CustomizationInjector.3") + e.getMessage(), e); //$NON-NLS-1$
            } catch (XPathExpressionException e) {
                NodeLogger.getLogger(getClass()).error(Messages.getString("CustomizationInjector.4") + e.getMessage(), e); //$NON-NLS-1$
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void injectData(final Document doc, final XPath xpath) throws XPathExpressionException {
        if (m_customizationInfo.containsKey("Logo")) { //$NON-NLS-1$
            injectLogo(doc, xpath);
        }
        if (m_customizationInfo.containsKey("WhereToGoFromHereLink") //$NON-NLS-1$
            && m_customizationInfo.containsKey("WhereToGoFromHereText")) { //$NON-NLS-1$
            injectLink(doc, xpath);
        }
        if (m_newIntro) {
            injectText(doc, xpath);
        }
    }

    /**
     * Adds the provided logo to the welcome page.
     *
     * @param doc
     * @param xpath
     * @param introNode
     * @throws XPathExpressionException
     */
    private void injectLogo(final Document doc, final XPath xpath) throws XPathExpressionException {
        Element welcomePageHeader =
            (Element)xpath.evaluate("//div[@id='welcome-page-header']", doc.getDocumentElement(), XPathConstants.NODE); //$NON-NLS-1$
        Element logo = doc.createElement("img"); //$NON-NLS-1$
        logo.setAttribute("style", "float:right;"); //$NON-NLS-1$ //$NON-NLS-2$
        logo.setAttribute("src", m_customizationInfo.get("Logo")); //$NON-NLS-1$ //$NON-NLS-2$
        logo.setAttribute("width", "200"); //$NON-NLS-1$ //$NON-NLS-2$
        logo.setAttribute("height", "52"); //$NON-NLS-1$ //$NON-NLS-2$
        welcomePageHeader.appendChild(logo);
    }

    /**
     * Adds the provided link to the "Where to go from here"-section.
     *
     * @param doc
     * @param xpath
     * @param wtgfhNode
     * @throws XPathExpressionException
     */
    private void injectLink(final Document doc, final XPath xpath) throws XPathExpressionException {
        Element wtgfhNode =
            (Element)xpath.evaluate("//div[@id='links']", doc.getDocumentElement(), XPathConstants.NODE); //$NON-NLS-1$
        Element linkWrapper = doc.createElement("div"); //$NON-NLS-1$
        linkWrapper.setAttribute("class", "floating-link"); //$NON-NLS-1$ //$NON-NLS-2$

        Element newLink = doc.createElement("a"); //$NON-NLS-1$
        newLink.setAttribute("href", m_customizationInfo.get("WhereToGoFromHereLink")); //$NON-NLS-1$ //$NON-NLS-2$
        newLink.setAttribute("class", "useful-links"); //$NON-NLS-1$ //$NON-NLS-2$

        Element iconSpanNode = doc.createElement("span"); //$NON-NLS-1$
        iconSpanNode.setAttribute("class", "icon-angle-circled-right"); //$NON-NLS-1$ //$NON-NLS-2$

        Text linkText = doc.createTextNode(m_customizationInfo.get("WhereToGoFromHereText")); //$NON-NLS-1$

        Element textSpanNode = doc.createElement("span"); //$NON-NLS-1$
        textSpanNode.appendChild(linkText);

        newLink.appendChild(iconSpanNode);
        newLink.appendChild(textSpanNode);

        linkWrapper.appendChild(newLink);

        wtgfhNode.appendChild(linkWrapper);

        //Adjust the boxes to the right size
        wtgfhNode.setAttribute("style", "height:200px"); //$NON-NLS-1$ //$NON-NLS-2$
        ((Element)xpath.evaluate("//div[@id='mru']", doc.getDocumentElement(), XPathConstants.NODE)) //$NON-NLS-1$
            .setAttribute("style", "height:200px"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Replaces the welcome-text with the provided one.
     *
     * @param doc
     * @param xpath
     * @param introNode
     * @throws XPathExpressionException
     */
    private void injectText(final Document doc, final XPath xpath) throws XPathExpressionException {
        Element introNode =
            (Element)xpath.evaluate("//div[@id='intro-text']", doc.getDocumentElement(), XPathConstants.NODE); //$NON-NLS-1$
        doc.adoptNode(m_newIntroNode);
        introNode.getParentNode().replaceChild(m_newIntroNode, introNode);
    }

}
