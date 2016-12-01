/* Copyright 2012, UCAR/Unidata.
   See the LICENSE file for more information.
*/

package dap4.test;

import dap4.d4ts.D4TSServlet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;


/* This is an extension of D4TSServlet to allow it to be tested
as using Spring mocking
*/

@Controller
@RequestMapping("/d4ts")
public class D4TSController extends D4TSServlet
{

    @Autowired
    private ServletContext servletContext;

    @Override
    public void
    initialize()
    {
        if(this.servletContext != null)
            super.setservletcontext(this.servletContext);
        super.initialize();
    }

    //////////////////////////////////////////////////
    // Constructor(s)

    public D4TSController()
    {
        super();
    }

    //////////////////////////////////////////////////

    @RequestMapping("**")
    public void handleRequest(HttpServletRequest req, HttpServletResponse res)
            throws IOException
    {
        super.handleRequest(req, res);
    }

}
