/* Copyright 2012, UCAR/Unidata.
   See the LICENSE file for more information.
*/

package dap4.test;

import dap4.d4ts.D4TSServlet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.ServletContextAware;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;


/* This is an extension of D4TSServlet to allow it to be tested
as using Spring mocking
*/

@Controller
@RequestMapping("/d4ts")
public class D4TSController extends D4TSServlet implements ServletContextAware
{

    @Autowired
    private ServletContext servletContext;


    public void setServletContext(ServletContext servletContext)
    {
        //if(servletContext == null)
            this.servletContext = servletContext;
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
        if(servletContext != null && this.servlet_context == null) ;
        set_servlet_context(req.getServletContext());
        super.handleRequest(req, res);
    }

}
