package org.infinispan.commons.test;

import java.io.File;
import java.util.Map;
import java.util.Properties;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.testng.ITestContext;
import org.testng.ITestResult;
import org.testng.TestRunner;
import org.testng.internal.TestResult;
import org.testng.reporters.JUnitXMLReporter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public class JUnitXMLReporter4Ispn extends JUnitXMLReporter {
    @Override
    protected void generateReport(ITestContext context) {
        TestRunner tr = (TestRunner) context;
        // Avoid super.generateReport() to not create the "Command line suite" directory
        tr.setOutputDirectory(
                String.format("%s%ssurefire-reports-junit-tr", System.getProperty("build.directory"), File.separator));

        super.generateReport(context);

        // Simplified name generator. see JUnitXMLReporter for the full name
        // generation logic, if needed.
        File xmlFile = new File(context.getOutputDirectory(), context.getName() + ".xml");

        // Inject system properties into the XML file
        try {
            injectSystemProperties(xmlFile);
        } catch (Exception e) {
            System.err.println("Failed to inject properties into " + xmlFile.getName() + ": " + e.getMessage());
        }

    }

    private void injectSystemProperties(File xmlFile) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(xmlFile);

        Element testsuite = (Element) doc.getElementsByTagName("testsuite").item(0);
        Element propertiesEl = doc.createElement("properties");

        Properties sysProps = System.getProperties();
        for (Map.Entry<Object, Object> entry : sysProps.entrySet()) {
            Element property = doc.createElement("property");
            property.setAttribute("name", entry.getKey().toString());
            property.setAttribute("value", entry.getValue().toString());
            propertiesEl.appendChild(property);
        }

        // Insert <properties> as the first child of <testsuite>
        Node firstChild = testsuite.getFirstChild();
        testsuite.insertBefore(propertiesEl, firstChild);

        // Save XML
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");

        DOMSource source = new DOMSource(doc);
        StreamResult result = new StreamResult(xmlFile);
        transformer.transform(source, result);
    }

    @Override
    public void onTestSuccess(ITestResult iTr) {
        //System.err.println("onTestSuccess");
        TestResult tr = (TestResult) iTr;
        int parameterIndex = tr.getParameterIndex();
        tr.setTestName(tr.getMethod().getMethodName() + (parameterIndex >= 0 ? " [" + parameterIndex + "]" : ""));
        //System.err.println("onTestSuccess " + tr.getMethod().getMethodName());
        super.onTestSuccess(tr);
    }

    @Override
    public void onTestFailedButWithinSuccessPercentage(ITestResult iTr) {
        TestResult tr = (TestResult) iTr;
        int parameterIndex = tr.getParameterIndex();
        tr.setTestName(tr.getTestName() + (parameterIndex >= 0 ? " [" + parameterIndex + "]" : ""));
        //System.err.println("onTestWIthi " + tr.getTestName());
        super.onTestFailedButWithinSuccessPercentage(tr);
    }

    @Override
    public void onTestFailure(ITestResult iTr) {
        TestResult tr = (TestResult) iTr;
        int parameterIndex = tr.getParameterIndex();
        tr.setTestName(tr.getTestName() + (parameterIndex >= 0 ? " [" + parameterIndex + "]" : ""));
        //System.err.println("onTestFailure " + tr.getTestName());
        super.onTestFailure(tr);
    }

    /**
     * Invoked each time a test is skipped.
     */
    @Override
    public void onTestSkipped(ITestResult iTr) {
        TestResult tr = (TestResult) iTr;
        int parameterIndex = tr.getParameterIndex();
        tr.setTestName(tr.getTestName() + (parameterIndex >= 0 ? " [" + parameterIndex + "]" : ""));
        //System.err.println("onTestSkipped " + tr.getTestName());
        super.onTestSkipped(tr);
    }

}
